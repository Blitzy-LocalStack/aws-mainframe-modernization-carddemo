/**
 * @file Component test for the bill-payment screen, `/billpay`.
 *
 * Purpose
 * -------
 * This file is the only verification the bill-payment screen receives, and that is a measured
 * statement rather than a lament. `tests/README.md` section 1.1 records that the online `CO*`
 * programs "cannot run end-to-end without a CICS runtime (absent on the runner)" and that "only
 * their extractable field-validation logic is unit-tested", so the three-layer COBOL suite is the
 * parity oracle for the BATCH chain and NO golden master exists for `app/cbl/COBIL00C.cbl`. Bill
 * payment is nonetheless one of the flows AAP section 0.9.4 names in the user's own acceptance
 * criteria, alongside sign-on, account view/update, card list/update, transaction add/list and the
 * posting batch — so the assertions below are the whole of that flow's front-end evidence.
 *
 * Subject: the default export of `ui/src/screens/billPay/index.tsx`, the migration target of BMS
 * mapset `COBIL00` / map `COBIL0A` and program `COBIL00C`, mounted by `ui/src/router.tsx` at
 * `/billpay`. Twenty-four `DFHMDF` fields make it the smallest of the base screens; it is also the
 * only small one that moves money, which is why the balance and the confirmation carry more
 * assertions here than the field widths do.
 *
 * Parameters (module analogue)
 * ---------------------------
 * None. The module takes no arguments and reads no environment value. Everything it needs it either
 * imports from the whitelisted dependency set or builds in the fixture helpers below. No
 * request-interception library is installed in this package -- `ui/package.json` declares none -- so
 * the one collaborator that would otherwise reach a network is replaced by
 * `vi.mock('../api/transactions')`; no endpoint is named anywhere in this file and no credential is
 * read.
 *
 * Returns (module analogue)
 * -------------------------
 * Nothing. Evaluating this module registers seven suites with the runner; the outcome is the
 * runner's pass or fail verdict, reported per case.
 *
 * Exceptions (module analogue)
 * ----------------------------
 * A failing assertion throws, which is how a case reports. `requestOfCall` and `answerInDialogue`
 * below throw deliberately when a fixture is used against a state that cannot satisfy it, so a
 * mis-wired case fails at its own line rather than several assertions later.
 *
 * Documentation obligation, and why two documents are cited for it
 * ---------------------------------------------------------------
 * The project's single user-specified rule, Rule 1 "Explainability", requires a docstring on every
 * function and module entry point stating purpose, parameters, returns and exceptions, plus an
 * inline comment justifying each non-obvious decision under one of four named categories.
 * `tests/README.md` section 12 imposes the identical obligation on "every new test, fixture builder,
 * helper, mock, and runner routine" and calls it "a hard review gate". The two AGREE, so this file
 * extends an established house convention rather than importing a foreign one, and
 * `docs/CODE_DOCUMENTATION_STANDARD.md` is the written form both are honoured through.
 *
 * Assumptions: the rationale labels below are the PLURAL forms `Assumptions:` and `Trade-offs:`,
 * and the singular abbreviations are not used anywhere in this file. That is not a style
 * preference: `docs/CODE_DOCUMENTATION_STANDARD.md` fixes one permitted written form per label and
 * `config/rule1/rule1_gate.py` fails the build on a singular stem, on a parenthesised label and on
 * an emphasised one. The same gate forbids a statement-level narration comment outside a file's
 * leading header block in every `.ts` and `.tsx` file, so no such comment appears here at all —
 * not even in this header, which keeps the header-block boundary from ever being the thing that
 * decides whether this file passes.
 *
 * Assumptions: every runner API used below is IMPORTED by name. `ui/vitest.config.ts` sets
 * `globals: true`, but `ui/tsconfig.json` keeps `"types": []`, so no ambient declaration is
 * reachable and `tsc --noEmit` fails on an un-imported `describe`, `it`, `expect` or `vi`. Both
 * files document that pairing as deliberate — injected at run time, undeclared at compile time, so
 * that a stray `expect` inside a SCREEN is a named typecheck failure — and every existing test file
 * in this package imports them, `ui/src/test/setup.ts` included.
 *
 * Assumptions: no `afterEach` is registered here. `ui/src/test/setup.ts` already unmounts the tree
 * and discards a seeded session after every case, and `ui/vitest.config.ts` sets `clearMocks` and
 * `restoreMocks`, so a manual reset would either duplicate that or fight it. The one consequence
 * worth stating is that `restoreMocks` clears the mocked function's implementation between cases,
 * which is why every case that drives a turn installs its own answer through `answerWith`.
 *
 * Two divergences from this file's own brief, both recorded rather than absorbed
 * ----------------------------------------------------------------------------
 * Refactoring Rationale: the brief asked for the literal `'*'` blank marker to be asserted beside
 * the field error, on the strength of the templated highlight at `app/cpy/CSSETATY.cpy` L17-L27.
 * That copybook is not reachable from this program. `COBIL00C`'s `COPY` list is `COCOM01Y`,
 * `COBIL00`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CVACT01Y`, `CVACT03Y`, `CVTRA05Y`, `DFHAID` and
 * `DFHBMSCA` — no `CSSETATY` — and a repository-wide search finds exactly one program that copies
 * it, `app/cbl/COACTUPC.cbl`. So this screen's baseline produces no per-field colour and no marker
 * at all; its only per-field signal is `MOVE -1 TO ACTIDINL` and `MOVE -1 TO CONFIRML`, the cursor
 * placement. The marker's ABSENCE is therefore asserted, with that measurement cited, and the
 * screens that do carry it — card update, transaction detail, user update — keep it because their
 * programs do.
 *
 * Refactoring Rationale: the brief asked for `'Invalid value. Valid values are (Y/N)...'` to be
 * provoked by an invalid confirmation character. The single-character `CONFIRM` field is replaced
 * by a confirmation dialogue whose two controls send `'Y'` and `'N'` and nothing else, so no third
 * character can be submitted and the branch at `app/cbl/COBIL00C.cbl` L187 is unreachable from this
 * screen. What is asserted instead is the pair of properties that actually hold: the dialogue
 * offers exactly the two answers the mapset's own `(Y/N)` hint names, and the catalog carries L187's
 * sentence byte-for-byte for the case where the service raises it.
 *
 * Trade-offs: the in-flight keyboard lock is deliberately NOT re-asserted here.
 * `ui/src/screens/mutationTurnLock.test.tsx` already drives this screen's reporting turn, its
 * paying turn and its advertised exit key against a deferred transport, and a second copy of that
 * case would be a second thing to keep in step with the screen for no additional coverage. The same
 * reasoning excludes the PF13-to-PF24 aliasing and the unbound terminal identifiers, which
 * `ui/src/layout/usePfKeys.ts`'s own suite owns, and the frame's title band and message-band width,
 * which belong to `ui/src/layout/appShell.test.tsx` and `ui/src/layout/MessageBand.test.tsx`.
 */

import { screen, waitFor, within } from '@testing-library/react';
import { useLocation } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';

import type { BillPaymentOutcome, BillPaymentRequest } from '../api/types';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP } from '../hooks/useAuth';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import type { CicsAid } from '../layout/usePfKeys';
import {
  BILL_PAY_CONFIRM_DOMAIN_HINT,
  BILL_PAY_FIELD_LABELS,
  BILL_PAY_KEY_LABELS,
  BILL_PAY_TITLE,
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  MESSAGE_BAND,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  PROGRAM_SOURCE_FILES,
  SCREEN_TITLES,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  messageBandWidthForMapset,
} from '../messages/messages';
import { BILL_PAY_PATH } from '../router';
import { FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';
import {
  apiError,
  expectMaxLength,
  expectVerbatimMessage,
  fieldError,
  pressPfKey,
  renderInAppShell,
  seedSession,
} from './setup';
import type { HarnessRenderResult } from './setup';

/**
 * Builds the mocked surface of the transaction transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory held in a `const`.
 * Vitest lifts every `vi.mock` call above the imports, so a `const` factory would sit in its
 * temporal dead zone at registration time — the same reason
 * `ui/src/screens/accountView/accountViewShell.test.tsx` writes its factory this way.
 *
 * Assumptions: ONE function, because the screen imports one. `ui/src/screens/billPay/index.tsx`
 * takes `payAccountBalanceInFull` from `../../api/transactions` and reaches
 * `ui/src/api/accounts.ts` not at all — the balance arrives on the payment contract's own preview
 * rather than from a separate account read — so mocking a second module would substitute a
 * collaborator this screen never calls.
 * @returns {Record<string, unknown>} The one transport function this screen imports, as a spy.
 */
function mockTransactionTransportModule(): Record<string, unknown> {
  return { payAccountBalanceInFull: vi.fn() };
}

vi.mock('../api/transactions', mockTransactionTransportModule);

/**
 * The program this screen migrates, as the message catalog records its path.
 *
 * Assumptions: the citation is READ from the catalog rather than typed here, so a case that quotes
 * a line number quotes it against the same provenance record the sentence itself comes from.
 */
const PROGRAM = PROGRAM_SOURCE_FILES.COBIL00C;

/** Every sentence `app/cbl/COBIL00C.cbl` owns, keyed by the catalog under its program name. */
const BILL_PAY_MESSAGES = PROGRAM_MESSAGES.COBIL00C;

/** The line each of those sentences is transcribed from, for the citations the cases carry. */
const BILL_PAY_MESSAGE_LINES = PROGRAM_MESSAGE_SOURCES.COBIL00C;

/**
 * Declared width of the confirmation field, `CONFIRMI PIC X(1)`.
 *
 * Assumptions: one character, from `app/cpy-bms/COBIL00.CPY` L72 and `CONFIRM LENGTH=1` at
 * `app/bms/COBIL00.bms` L115-L119. The field itself is replaced by a dialogue, so the width
 * survives as a constraint on the ANSWERS rather than on a control: each of the two the dialogue
 * offers is exactly this wide, which is what makes them values the reference's `EVALUATE CONFIRMI`
 * at `app/cbl/COBIL00C.cbl` L173-L191 could have received.
 */
const CONFIRMATION_DECLARED_WIDTH = 1;

/**
 * Declared width of the transaction-identifier slot, `TRNNAMEI PIC X(4)`.
 *
 * Assumptions: four characters, from `app/cpy-bms/COBIL00.CPY` L24 and `TRNNAME LENGTH=4` at
 * `app/bms/COBIL00.bms` L34-L37.
 */
const TRANSACTION_ID_DECLARED_WIDTH = 4;

/**
 * Declared width of the program-name slot, `PGMNAMEI PIC X(8)`.
 *
 * Assumptions: eight characters, from `app/cpy-bms/COBIL00.CPY` L42 and `PGMNAME LENGTH=8` at
 * `app/bms/COBIL00.bms` L57-L60.
 */
const PROGRAM_NAME_DECLARED_WIDTH = 8;

/**
 * Route PF3 returns to when the arriving screen declared no origin.
 *
 * Assumptions: the main menu, because `app/cbl/COBIL00C.cbl` L128-L135 substitutes
 * `MOVE 'COMEN01C' TO CDEMO-TO-PROGRAM` whenever `CDEMO-FROM-PROGRAM` is blank or low-values, and
 * `COMEN01C` is the main-menu program. AAP rule T5 maps `EXEC CICS XCTL` to a client-side route
 * change, so the migrated form of that substitution is this address.
 *
 * Alternatives Considered: importing the constant from `ui/src/routes/navigation.ts`, which is where
 * the screen itself reads it. Rejected because that module is not in this file's declared dependency
 * set, and because reading the expected value out of the same module the subject reads it from would
 * make the assertion pass for any value the two happened to agree on. Writing it here with its
 * citation makes the assertion a statement about the reference instead.
 */
const MAIN_MENU_DESTINATION = '/menu';

/** The account identifier every case looks up, eleven digits wide as `ACCT-ID PIC 9(11)` declares. */
const FIXTURE_ACCOUNT_ID = '00000000011';

/**
 * Attention identifiers this mapset's legend does not advertise and its program does not dispatch.
 *
 * Assumptions: these four and no others. `app/cbl/COBIL00C.cbl` L125-L142 evaluates `DFHENTER`,
 * `DFHPF3` and `DFHPF4` and sends everything else to `WHEN OTHER`, and the row-24 legend at
 * `app/bms/COBIL00.bms` L131-L135 advertises exactly the same three. PF5 is the interesting absence
 * and is listed first for that reason: a screen that moves money has no Save key, because the
 * confirm-then-answer pair IS the commit. PF7, PF8 and PF12 are the paging and cancel keys other
 * mapsets carry, and their absence here is the baseline's own rather than an omission in the
 * migration.
 */
const UNADVERTISED_AIDS: readonly CicsAid[] = ['PFK05', 'PFK07', 'PFK08', 'PFK12'];

/**
 * Reports the address the router is showing, so a key's destination can be observed.
 *
 * Alternatives Considered: asserting that the bill-payment screen unmounted after PF3, which needs
 * no probe at all. Rejected because unmounting is consistent with any destination, including one
 * outside this application, whereas the reference can only ever return to a CardDemo program — so
 * the assertion has to name the address and not merely the departure.
 * @returns {ReactElement} A node carrying the current pathname as its text.
 */
function AddressProbe(): ReactElement {
  return <span data-testid="probed-address">{useLocation().pathname}</span>;
}

/**
 * Builds the unconfirmed answer the service returns for an account with a payable balance.
 *
 * Assumptions: the sentence is the catalog's `CONFIRM_TO_MAKE_A_BILL_PAYMENT` and is not retyped,
 * because the screen's `previewTurn` classifies the outcome by comparing the sentence it received
 * against that same constant. A fixture carrying a paraphrase would be classified as the
 * unrecognised outcome, which fails closed and withholds the payment control — so a retyped
 * sentence here would not merely weaken a case, it would change which branch the case exercises.
 * @param {string} payableBalance - The balance as the service publishes money: a plain decimal
 *   string with two decimal positions and no grouping separator.
 * @returns {BillPaymentOutcome} A `PREVIEWED` outcome offering payment of that balance.
 */
function payableBalanceOf(payableBalance: string): BillPaymentOutcome {
  return {
    outcome: 'PREVIEWED',
    preview: {
      accountId: FIXTURE_ACCOUNT_ID,
      payableBalance,
      paid: false,
      returnMessage: BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT,
    },
  };
}

/**
 * Builds the unconfirmed answer the service returns when there is nothing to pay.
 *
 * Assumptions: the balance is still REPORTED on this turn rather than withheld. The reference
 * reaches its advisory by the ordinary route — move a sentence, send the map — after
 * `app/cbl/COBIL00C.cbl` L193-L194 has already moved the record field into `CURBALI`, so the figure
 * the operator is told they need not pay is on screen beside the advisory.
 * @param {string} payableBalance - The non-positive balance the service read, as a decimal string.
 * @returns {BillPaymentOutcome} A `PREVIEWED` outcome carrying the nothing-to-pay advisory.
 */
function nothingToPayOn(payableBalance: string): BillPaymentOutcome {
  return {
    outcome: 'PREVIEWED',
    preview: {
      accountId: FIXTURE_ACCOUNT_ID,
      payableBalance,
      paid: false,
      returnMessage: BILL_PAY_MESSAGES.YOU_HAVE_NOTHING_TO_PAY,
    },
  };
}

/**
 * Builds the answer the service returns for a written payment.
 *
 * Assumptions: the identifier is sixteen digits, because `TRAN-ID` is `PIC X(16)` at
 * `app/cpy/CVTRA05Y.cpy` L5 fed from a `PIC 9(16)` counter, so it arrives zero-padded with no
 * embedded blank for the template's `DELIMITED BY SPACE` clause to truncate at.
 *
 * Assumptions: the balance the response reports is `'0.00'`, which is what
 * `COMPUTE ACCT-CURR-BAL = ACCT-CURR-BAL - TRAN-AMT` at `app/cbl/COBIL00C.cbl` L234 leaves behind
 * when the amount paid is the whole balance. The screen does not paint it — L523-L532 clears the
 * fields before composing the success sentence — so the member is present because the contract
 * declares it, not because a case reads it.
 * @param {string} transactionId - Identifier the service reports for the written payment row.
 * @returns {BillPaymentOutcome} A `PAID` outcome for that identifier.
 */
function writtenPaymentOf(transactionId: string): BillPaymentOutcome {
  return {
    outcome: 'PAID',
    payment: {
      transactionId,
      accountId: FIXTURE_ACCOUNT_ID,
      currentBalance: '0.00',
      paid: true,
      returnMessage: null,
    },
  };
}

/**
 * Installs the answer the mocked transport is to give for every turn in the current case.
 *
 * Assumptions: an implementation is installed per CASE and never once for the file, because
 * `ui/vitest.config.ts` sets `restoreMocks`, which clears a spy's implementation between cases. A
 * case that drove a turn without installing one would call a function returning `undefined`, and the
 * screen's `.then` would fail on that rather than on anything it asserts.
 * @param {BillPaymentOutcome} outcome - The outcome the service is to report.
 * @returns {Promise<void>} Resolves once the mocked module has been resolved and armed.
 */
async function answerWith(outcome: BillPaymentOutcome): Promise<void> {
  const { payAccountBalanceInFull } = await import('../api/transactions');
  vi.mocked(payAccountBalanceInFull).mockResolvedValue(outcome);
}

/**
 * Installs a rejection that is NOT one of the client's own normalised failures.
 *
 * Assumptions: a bare error is the honest fixture for this file, and the reason is a dependency
 * boundary rather than a preference. The screen recognises an attributed refusal with
 * `isApiRequestError`, whose class lives in `ui/src/api/client.ts` — a module outside this file's
 * declared dependency set — so a case here cannot construct one. What it CAN construct is the other
 * documented arm: `billPayFailure` treats a value the guard rejects as "the lookup did not
 * complete", which is the condition `'Unable to lookup Account...'` names at
 * `app/cbl/COBIL00C.cbl` L368, and answers a failed payment with L543's own wording instead.
 *
 * Trade-offs: the attributed-refusal path is therefore exercised through the screen's exported
 * `resolveApiFieldErrors` and the locally raised blank-entry refusal rather than through a rejected
 * request. The rendered outcome asserted is the same one — a marked field, its help text, and a
 * sentence on the row-23 line — and the arrangement keeps every import in this file inside the
 * declared set.
 * @returns {Promise<void>} Resolves once the mocked module has been resolved and armed.
 */
async function refuseWithATransportThatDidNotComplete(): Promise<void> {
  const { payAccountBalanceInFull } = await import('../api/transactions');
  vi.mocked(payAccountBalanceInFull).mockRejectedValue(new Error('the request did not complete'));
}

/**
 * Mounts the screen inside the real application frame at its own address.
 *
 * Assumptions: the frame is mounted because three of the four contracts this file asserts live in
 * it. The screen composes no title band, no row-23 message line and no row-24 legend of its own —
 * it publishes all three through `useShellSlot` — so a bare render would leave the message text and
 * the function-key legend unqueryable, and the case would be asserting a tree the router never
 * builds.
 *
 * Assumptions: `routePath` is the address `ui/src/router.tsx` declares, imported rather than
 * spelled, so the subject is mounted at the pattern the application mounts it at and the harness's
 * own matcher check has something real to verify.
 * @returns {Promise<HarnessRenderResult>} The render result, with the keyboard operator attached.
 * @throws {Error} If the route pattern cannot match the address, which the harness reports.
 */
async function mountBillPay(): Promise<HarnessRenderResult> {
  const BillPayScreen = (await import('../screens/billPay')).default;
  return await renderInAppShell(<BillPayScreen />, {
    initialEntries: [BILL_PAY_PATH],
    routePath: BILL_PAY_PATH,
  });
}

/**
 * Mounts the screen with an address probe beside it, for a case that asserts a destination.
 *
 * Assumptions: no `routePath` is passed, so the harness mounts the subject at its wildcard child
 * route. That is what keeps the probe mounted after a navigation: pinned to `/billpay`, the tree
 * would unmount on the very transition the case exists to observe and the probe would have nothing
 * left to report.
 *
 * Trade-offs: the screen therefore also stays mounted after the navigation, which the real route
 * table would not do. Accepted because the assertion is about the ADDRESS the key selects, and the
 * departure itself is asserted by `ui/src/routerReachability.test.tsx` against the real table.
 * @returns {Promise<HarnessRenderResult>} The render result, with the keyboard operator attached.
 */
async function mountBillPayWithAddressProbe(): Promise<HarnessRenderResult> {
  const BillPayScreen = (await import('../screens/billPay')).default;
  return await renderInAppShell(
    <>
      <BillPayScreen />
      <AddressProbe />
    </>,
    { initialEntries: [BILL_PAY_PATH] },
  );
}

/**
 * Locates the account entry by the literal the mapset paints beside it.
 *
 * Assumptions: the control is found through its LABEL rather than by role or test identifier,
 * because the label is the association an operator and a screen reader both use, and the literal is
 * the catalog's — `'Enter Acct ID:'`, `LENGTH=14` at `app/bms/COBIL00.bms` L80-L84. Querying by
 * label therefore asserts the caption and the control in one step.
 * @returns {HTMLInputElement} The account entry.
 * @throws {Error} If no control carries that label, which Testing Library raises.
 */
function accountEntry(): HTMLInputElement {
  const control = screen.getByLabelText(BILL_PAY_FIELD_LABELS.accountId);
  if (!(control instanceof HTMLInputElement)) {
    throw new Error('the account entry is not an input element');
  }
  return control;
}

/**
 * Locates the row-23 message band the screen publishes into the frame.
 * @returns {HTMLElement} The band element.
 * @throws {Error} If the band is absent, which Testing Library raises.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Locates the balance the screen paints, whether or not it currently holds a figure.
 *
 * Assumptions: the element is present on every turn and empty rather than absent when no balance
 * has been reported, which is why this is a `getBy` and not a `queryBy`. `CURBAL` is a painted field
 * on the mapset at every turn too — `INITIALIZE-ALL-FIELDS` at `app/cbl/COBIL00C.cbl` L558-L566
 * moves spaces into it rather than removing it.
 *
 * Assumptions: the handle is written as a literal because the screen writes it as one —
 * `ui/src/screens/billPay/index.tsx` L1321 paints `data-testid="billpay-current-balance"` inline and
 * exports no constant for it, so there is nothing to import. Naming the publishing line here is
 * what makes a drift between the two sides traceable to a file and a line rather than to a
 * mysteriously empty query.
 * @returns {HTMLElement} The balance element.
 * @throws {Error} If the element is absent, which Testing Library raises.
 */
function paintedBalance(): HTMLElement {
  return screen.getByTestId('billpay-current-balance');
}

/**
 * Locates the control that opens the confirmation dialogue.
 *
 * Assumptions: the handle is a literal for the same reason the balance's is —
 * `ui/src/screens/billPay/index.tsx` L1545 paints `data-testid="billpay-confirm"` inline and exports
 * no constant. It is queried by test identifier rather than by caption because the trigger and the
 * dialogue's own affirmative answer both carry the `'Y'` caption of `app/cbl/COBIL00C.cbl` L174, so
 * a query by name is ambiguous the moment the dialogue is open.
 * @returns {HTMLButtonElement} The trigger.
 * @throws {Error} If the trigger is absent or is not a button.
 */
function confirmationTrigger(): HTMLButtonElement {
  const trigger = screen.getByTestId('billpay-confirm');
  if (!(trigger instanceof HTMLButtonElement)) {
    throw new Error('the confirmation trigger is not a button element');
  }
  return trigger;
}

/**
 * Locates one control in the row-24 legend by the caption the mapset paints for it.
 *
 * Assumptions: the accessible name IS the caption, because `ui/src/layout/PfKeyBar.tsx` renders the
 * descriptor's label as the button's only content and adds no separate label — so a query by name
 * asserts the legend spelling at the same time as it finds the control.
 * @param {string} caption - The legend caption, taken from the message catalog.
 * @returns {HTMLElement} The legend control.
 * @throws {Error} If no legend control carries that caption, which Testing Library raises.
 */
function legendControl(caption: string): HTMLElement {
  return within(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).getByRole(
    'button',
    { name: caption },
  );
}

/**
 * Reads the request the mocked transport received on one turn.
 * @param {number} turn - Zero-based index of the turn, in call order.
 * @returns {Promise<BillPaymentRequest>} The request that turn sent.
 * @throws {Error} If no such turn was taken, which names the index rather than failing later on a
 *   property of `undefined`.
 */
async function requestOfCall(turn: number): Promise<BillPaymentRequest> {
  const { payAccountBalanceInFull } = await import('../api/transactions');
  const request = vi.mocked(payAccountBalanceInFull).mock.calls[turn]?.[0];
  if (request === undefined) {
    throw new Error(`no turn was taken at index ${String(turn)}`);
  }
  return request;
}

/**
 * Types an account identifier and takes the reporting turn with the Enter key.
 *
 * Assumptions: the entry is typed and the key pressed, rather than the state being set directly,
 * because the digit filter and the keyboard binding are both part of what a turn exercises. Enter
 * pressed while the caret is in the account field reaches the screen's binding: `usePfKeys` claims
 * Enter only for buttons, links, selects and textareas, and a text input is on none of those lists.
 * @param {HarnessRenderResult['user']} user - The keyboard operator from the render result.
 * @param {string} accountId - The identifier to type.
 * @returns {Promise<void>} Resolves once the turn has been dispatched.
 */
async function takeReportingTurn(
  user: HarnessRenderResult['user'],
  accountId: string,
): Promise<void> {
  await user.type(accountEntry(), accountId);
  await user.keyboard('{Enter}');
}

/**
 * Opens the confirmation dialogue and answers it.
 *
 * Assumptions: the answer is located INSIDE the dialogue rather than by name across the document,
 * because the trigger carries the same `'Y'` caption as the confirming control — the mapset's own
 * `(Y/N)` domain, offered twice by design — so an unscoped query would match two elements and a
 * first-match query could activate the trigger a second time instead of answering.
 * @param {HarnessRenderResult['user']} user - The keyboard operator from the render result.
 * @param {string} answer - The single character to answer with, `'Y'` or `'N'`.
 * @returns {Promise<void>} Resolves once the answer has been activated.
 * @throws {Error} If the dialogue does not offer that answer, which names it.
 */
async function answerInDialogue(user: HarnessRenderResult['user'], answer: string): Promise<void> {
  await user.click(confirmationTrigger());
  const dialogue = await screen.findByRole('tooltip');
  const control = within(dialogue).getByRole('button', { name: answer });
  await user.click(control);
}

/**
 * Waits until the row-23 line carries exactly one catalogued sentence.
 *
 * Assumptions: the expected sentence is TRIMMED, because `normaliseMessageBandValue` in
 * `ui/src/messages/messages.ts` trims what it renders — the baseline pads a message field to its
 * declared width and the padding is a field width rather than content.
 *
 * ⚠️ Alternatives Considered: `expect(band).toHaveTextContent(sentence)`, which reads as the obvious
 * form and is the wrong one HERE. That matcher normalises the ELEMENT's whitespace before comparing,
 * collapsing a run of spaces to one, so it accepts a band emitting `'Payment successful. Your
 * Transaction ID is ...'` against the reference's `'Payment successful.  Your Transaction ID is
 * ...'` — measured, and the reason this helper compares raw text content instead. The doubled space
 * is content: `app/cbl/COBIL00C.cbl` L527-L531 joins a literal ending in a space to one beginning
 * with a space.
 *
 * Trade-offs: an exact comparison rather than a substring one, which means a band carrying the
 * sentence plus anything else fails. That is wanted — the row-23 field holds one message at a time.
 * @param {string} sentence - The catalogued sentence, from the message catalog.
 * @returns {Promise<void>} Resolves once the band carries exactly that sentence.
 */
async function waitForBandToRead(sentence: string): Promise<void> {
  await waitFor(
    /**
     * Asserts the band's current text.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(messageBand().textContent).toBe(sentence.trim());
    },
  );
}

/**
 * The account entry refuses a twelfth character and refuses a non-digit.
 *
 * Assumptions: eleven is asserted individually rather than through a table of the screen's three
 * fields, and the reason is that only one of the three is an editable control: the balance is
 * `ATTRB=(ASKIP,FSET,NORM)` at `app/bms/COBIL00.bms` L103-L106 and the confirmation is replaced by a
 * dialogue. Trade-offs: a table would have to carry a per-row "how do I assert this" column for
 * three rows, which is more machinery than three named assertions and hides the citation each row
 * exists to carry.
 *
 * Assumptions: three independent declarations in the reference agree on eleven — `ACTIDIN LENGTH=11`
 * at `app/bms/COBIL00.bms` L85-L89, `ACTIDINI PIC X(11)` at `app/cpy-bms/COBIL00.CPY` L60, and the
 * record field `ACCT-ID PIC 9(11)` at `app/cpy/CVACT01Y.cpy` L5 the entry is moved into — and the
 * carried selection `CDEMO-ACCT-ID PIC 9(11)` at `app/cpy/COCOM01Y.cpy` L38 agrees with all three.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theAccountEntryIsBoundedAndNumeric(): Promise<void> {
  const { BILL_PAY_FIELD_WIDTHS } = await import('../screens/billPay');
  const { user } = await mountBillPay();

  // WHY : Assumptions: the screen's published width is checked against the copybook figure as well
  //       as being used to assert the attribute, so a case cannot pass by agreeing with a constant
  //       that has drifted from `app/cpy-bms/COBIL00.CPY` L60.
  expect(BILL_PAY_FIELD_WIDTHS.accountId).toBe(11);
  expectMaxLength(accountEntry(), BILL_PAY_FIELD_WIDTHS.accountId);

  // WHY : Assumptions: the twelfth keystroke is dropped rather than rejected with a message, which
  //       is what the terminal did — a 3270 field of eleven positions simply ignored the next
  //       keystroke, and `maxLength` is where that hardware behaviour survives.
  await user.type(accountEntry(), '9999999999999');
  expect(accountEntry().value).toHaveLength(BILL_PAY_FIELD_WIDTHS.accountId);

  await user.clear(accountEntry());

  // WHY : Assumptions: a non-digit is filtered on the way in, not flagged. The receiving field is
  //       `PIC 9(11)` and COBOL's `IS NUMERIC` on it admits digits and nothing else — no sign, no
  //       separator, no space — so a value containing any of them is one the reference field could
  //       not have held. Trade-offs: filtering rather than refusing means a paste of mixed
  //       characters yields the digits it contained instead of a refusal the operator did not earn.
  await user.type(accountEntry(), 'ab12cd34');
  expect(accountEntry().value).toBe('1234');
}

/**
 * Exactly one control holds the cursor when the screen first paints.
 *
 * Assumptions: `app/bms/COBIL00.bms` carries exactly ONE `IC` attribute across its 24 `DFHMDF`
 * definitions — `ACTIDIN` at L85, `ATTRB=(FSET,IC,NORM,UNPROT)` — and `app/cbl/COBIL00C.cbl` L115
 * moves `-1` into `ACTIDINL` on first entry, so the cursor starts in the account entry whatever else
 * happens.
 *
 * Assumptions: the single initial cursor is asserted through `document.activeElement` and not
 * through an `autofocus` attribute, because React applies the `autoFocus` prop by CALLING focus
 * rather than by emitting the attribute — measured: the rendered input carries no `autofocus`
 * attribute at all. Alternatives Considered: counting `[autofocus]` elements, which would have
 * asserted zero and passed for the wrong reason on a screen that had lost the prop entirely.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theAccountEntryIsTheOnlyInitialCursor(): Promise<void> {
  await mountBillPay();

  expect(document.activeElement).toBe(accountEntry());

  // WHY : Assumptions: the confirmation control's own focus move is a DIFFERENT thing and must not
  //       be mistaken for a second `IC`. It is the `MOVE -1 TO CONFIRML` of
  //       `app/cbl/COBIL00C.cbl` L239, issued alongside the confirmation prompt on a later turn, on
  //       a panel that does not exist until the operator opens it.
  expect(confirmationTrigger()).not.toHaveFocus();
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).not.toHaveFocus();
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03)).not.toHaveFocus();
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04)).not.toHaveFocus();
}

/**
 * The balance is painted at its declared fourteen positions and is not editable.
 *
 * Assumptions: fourteen, and this mapset's fourteen is not the account screens' fifteen.
 * `CURBAL LENGTH=14` at `app/bms/COBIL00.bms` L103-L106 and `CURBALI PIC X(14)` at
 * `app/cpy-bms/COBIL00.CPY` L66 both declare it, and the picture behind it is
 * `WS-CURR-BAL PIC +9999999999.99` at `app/cbl/COBIL00C.cbl` L56 — one sign position, ten integer
 * positions, the point, two decimal positions. The account mapsets declare fifteen because their
 * picture is `+ZZZ,ZZZ,ZZZ.99`, so one underlying record field is painted at three different widths
 * across the application and unifying them would adopt another screen's presentation here.
 *
 * Assumptions: the positions are `9` and not `Z`, so nothing is zero-suppressed and a small balance
 * is zero-PADDED to the full width. That is why the rendered length is a fixed fourteen rather than
 * as many characters as the figure needs.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theBalanceIsPaintedAtItsDeclaredWidthAndIsReadOnly(): Promise<void> {
  const { BILL_PAY_FIELD_WIDTHS } = await import('../screens/billPay');
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  expect(BILL_PAY_FIELD_WIDTHS.currentBalance).toBe(14);

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  expect(paintedBalance().textContent).toHaveLength(BILL_PAY_FIELD_WIDTHS.currentBalance);

  // WHY : Assumptions: the balance is not a control at all, and the assertion is a COUNT of editable
  //       controls rather than a check on this element's tag, because the count is what would change
  //       if the figure the operator is being asked to pay ever became editable. The mapset makes it
  //       auto-skip, so the 3270 cursor could not enter it either.
  expect(screen.getAllByRole('textbox')).toHaveLength(1);
  expect(screen.getAllByRole('textbox')[0]).toBe(accountEntry());
}

/**
 * The confirmation offers exactly the two single-character answers the mapset's hint names.
 *
 * Assumptions: `CONFIRM` is `LENGTH=1` at `app/bms/COBIL00.bms` L115-L119 and `CONFIRMI PIC X(1)` at
 * `app/cpy-bms/COBIL00.CPY` L72, and `app/cbl/COBIL00C.cbl` L173-L191 accepts `'Y'`/`'y'`, `'N'`/`'n'`
 * or blank there and refuses everything else. The dialogue that replaces the field offers two
 * answers, each exactly one character, so every answer it can send is one that `EVALUATE CONFIRMI`
 * admits.
 *
 * Refactoring Rationale: this is why L187's refusal is unreachable from this screen. The field
 * existed because a terminal had no modal; a dialogue asks the question directly and cannot receive
 * a third character, so the branch that rejected one has no way to fire. The sentence is still
 * asserted — in the message suite below, against the catalog and for the case where the service
 * raises it — rather than dropped.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theConfirmationDomainIsTheTwoAnswersTheMapsetNames(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  // WHY : Assumptions: the five-character domain hint survives the field it annotated, because it
  //       still names the two answers the dialogue offers. It is `LENGTH=5` at
  //       `app/bms/COBIL00.bms` L122-L126, and its parentheses are part of the literal.
  expect(screen.getByText(BILL_PAY_CONFIRM_DOMAIN_HINT)).toBeInTheDocument();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitFor(
    /**
     * Waits for the payment to be offered.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationTrigger()).toBeEnabled();
    },
  );

  await user.click(confirmationTrigger());
  const dialogue = await screen.findByRole('tooltip');
  const answers = within(dialogue).getAllByRole('button');

  expect(answers).toHaveLength(2);
  for (const answer of answers) {
    expect(answer.textContent).toHaveLength(CONFIRMATION_DECLARED_WIDTH);
  }
  expect(within(dialogue).getByRole('button', { name: 'Y' })).toBeInTheDocument();
  expect(within(dialogue).getByRole('button', { name: 'N' })).toBeInTheDocument();
}

/**
 * The screen's two identity slots carry the widths their symbolic map declares.
 *
 * Assumptions: the two the SCREEN owns are asserted and the four the frame owns are not.
 * `TRNNAMEI PIC X(4)` at `app/cpy-bms/COBIL00.CPY` L24 and `PGMNAMEI PIC X(8)` at L42 are filled
 * from values this screen publishes, so their widths are this screen's contract. `TITLE01I` and
 * `TITLE02I` are `PIC X(40)` at L30 and L48 and are filled from `app/cpy/COTTL01Y.cpy`, whose two
 * literals the catalog carries with their declared widths — checked here against the catalog rather
 * than against the rendered band. `CURDATEI` and `CURTIMEI` are `PIC X(8)` at L36 and L54 and hold
 * runtime clock values formatted by `ui/src/layout/ScreenHeader.tsx`, so asserting them here would
 * be asserting the clock; that module's own suite owns them.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theIdentitySlotsCarryTheirDeclaredWidths(): Promise<void> {
  const { BILL_PAY_TRANSACTION_ID, BILL_PAY_PROGRAM_NAME } = await import('../screens/billPay');

  expect(BILL_PAY_TRANSACTION_ID).toHaveLength(TRANSACTION_ID_DECLARED_WIDTH);
  expect(BILL_PAY_PROGRAM_NAME).toHaveLength(PROGRAM_NAME_DECLARED_WIDTH);

  // WHY : Assumptions: the program name the screen publishes is the program this file is written
  //       against, and the catalog's provenance entry for that program names the same file. Checking
  //       the two against each other is what stops a citation in this file pointing at one program
  //       while the screen declares another.
  expect(PROGRAM).toContain(BILL_PAY_PROGRAM_NAME);

  expect(SCREEN_TITLES.TITLE01.text).toHaveLength(SCREEN_TITLES.TITLE01.declaredWidth);
  expect(SCREEN_TITLES.TITLE02.text).toHaveLength(SCREEN_TITLES.TITLE02.declaredWidth);
}

/**
 * The message line's content contract is narrower than the field that displays it, and stays so.
 *
 * Assumptions: the two numbers are BOTH real and neither is a correction of the other. `ERRMSG` is
 * `LENGTH=78` at `app/bms/COBIL00.bms` L127-L130 and `ERRMSGI PIC X(78)` at
 * `app/cpy-bms/COBIL00.CPY` L78 — the display field — while the shared work area that a message
 * crosses declares `CCARD-ERROR-MSG PIC X(75)` and `CCARD-RETURN-MSG PIC X(75)` at
 * `app/cpy/CVCRD01Y.cpy` L28-L30. So a sentence is composed to 75 and painted in a field of 78, and
 * "correcting" the 75 to 78 would widen a contract this mapset does not own.
 *
 * Trade-offs: this asserts the two DECLARED widths recorded in the message catalog and asserts
 * nothing about the band's rendered geometry. Gap G1 in AAP section 0.3.4 records that the fixed
 * character grid is not reproduced, and `ui/src/layout/MessageBand.test.tsx` owns the band's own
 * rendering.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function theMessageLineKeepsBothOfItsDeclaredWidths(): void {
  const displayWidth = messageBandWidthForMapset('COBIL00');

  expect(displayWidth).toBe(78);
  expect(MESSAGE_BAND.workAreaWidth).toBe(75);
  expect(MESSAGE_BAND.workAreaWidth).toBeLessThan(displayWidth);
}

/**
 * The screen paints its title and its fields through the design system, not through raw markup.
 *
 * Assumptions: AAP section 0.3.2 requires a library component wherever one exists, so a raw
 * `<input>`, `<button>`, `<select>`, `<table>` or heading element on this screen would be a
 * violation whatever it rendered. The check is expressed as "every control the screen owns is a
 * design-system control", which is falsifiable: an antd `Input` and `Button` each carry the
 * system's own class prefix, and a raw element carries none.
 *
 * Assumptions: the row-4 caption is a HEADING and not emphasised text, because it is the screen's
 * own name and assistive software needs it in the document outline. `'Bill Payment'` is
 * `LENGTH=12` at `app/bms/COBIL00.bms` L75-L79 and is read from the catalog here rather than typed.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theScreenComposesThroughTheDesignSystem(): Promise<void> {
  await mountBillPay();

  expect(screen.getByRole('heading', { name: BILL_PAY_TITLE })).toBeInTheDocument();

  expect(accountEntry().className).toContain('ant-input');
  expect(confirmationTrigger().className).toContain('ant-btn');
  for (const caption of [
    BILL_PAY_KEY_LABELS.ENTER,
    BILL_PAY_KEY_LABELS.PFK03,
    UNIFORM_PF_KEY_LABELS.PFK04,
  ]) {
    expect(legendControl(caption).className).toContain('ant-btn');
  }
}

/**
 * Registers the field-constraint and composition cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function fieldConstraintCases(): void {
  it(
    'bounds the account entry at eleven digits and filters the rest',
    theAccountEntryIsBoundedAndNumeric,
  );
  it(
    'gives the account entry the only initial cursor on the screen',
    theAccountEntryIsTheOnlyInitialCursor,
  );
  it(
    'paints the balance read-only at its declared fourteen positions',
    theBalanceIsPaintedAtItsDeclaredWidthAndIsReadOnly,
  );
  it(
    'offers exactly the two single-character confirmation answers',
    theConfirmationDomainIsTheTwoAnswersTheMapsetNames,
  );
  it(
    'carries the declared widths of its two identity slots',
    theIdentitySlotsCarryTheirDeclaredWidths,
  );
  it(
    'keeps a 75-character content contract inside a 78-character field',
    theMessageLineKeepsBothOfItsDeclaredWidths,
  );
  it('renders every control through the design system', theScreenComposesThroughTheDesignSystem);
}

describe('BillPayScreen field constraints', fieldConstraintCases);

/**
 * The two spellings of the account concept are both live in one program and neither is normalised.
 *
 * Assumptions: `app/cbl/COBIL00C.cbl` abbreviates to `'Acct ID'` at L161 and spells `'Account ID'`
 * in full at L361 and L392 — the same concept, two renderings, one program. Neither is a
 * transcription slip and neither is corrected: transformation rule T8 carries user-visible text
 * across character for character, so a migration that unified them would change what one of the two
 * turns paints. This is precisely the inconsistency a well-meaning refactor erases, which is why the
 * case asserts the DIFFERENCE and not only the two values.
 *
 * Assumptions: the line numbers are read from the catalog's provenance records rather than written
 * here, so the citations this file carries are checked against the same record the sentences come
 * from instead of being prose a reader has to verify by hand.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function bothSpellingsOfTheAccountConceptSurvive(): void {
  expect(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY).toContain('Acct ID');
  expect(BILL_PAY_MESSAGE_LINES.ACCT_ID_CAN_NOT_BE_EMPTY).toContain(161);

  expect(SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND).toContain('Account ID');
  // WHY : Assumptions: the provenance list is checked with a CONTAINS rather than an equality,
  //       because this sentence is shared: `app/cbl/COTRN02C.cbl` emits it too, so the catalog
  //       records two emitting programs. What this case is entitled to assert is that THIS program's
  //       three sites are among them, and an equality would fail the moment a fourth program was
  //       recorded without anything about this screen having changed.
  expect(SHARED_MESSAGE_SOURCES.ACCOUNT_ID_NOT_FOUND).toContainEqual({
    file: PROGRAM,
    lines: [361, 392, 425],
  });

  // WHY : Assumptions: the abbreviated sentence must NOT contain the full spelling, which is the
  //       assertion that actually fails if the two are ever unified — a check that each contains its
  //       own spelling would still pass after a merge onto the longer form.
  expect(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY).not.toContain('Account ID');
}

/**
 * The capitalisation and punctuation the baseline chose survive unchanged.
 *
 * Assumptions: four details in these sentences read as slips and none is one, and every one of them
 * is a literal in `app/cbl/COBIL00C.cbl`. `'can NOT be empty'` at L161 and `'NOT found'` at L361
 * capitalise the negation, so neither becomes `'cannot'` or `'not'`; `'Invalid value. Valid values
 * are (Y/N)...'` at L187 puts a full stop after the first word and parentheses around the domain;
 * and `'Unable to Update Account...'` at L399 capitalises the verb where `'Unable to lookup
 * Account...'` at L368 lowercases it, in the same program, one paragraph apart. The line numbers are
 * asserted from the catalog's provenance so that this file's citations and the catalog's cannot
 * drift apart silently.
 *
 * Alternatives Considered: retyping each sentence into the assertion, which reads more directly.
 * Rejected because a paraphrase in the screen and the same paraphrase in the test AGREE, so the case
 * would pass while the fidelity it exists to protect was gone. Asserting against the catalog makes
 * the screen agree with the catalog instead of with this file; asserting the substrings on top of
 * that is what catches a normalisation inside the catalog itself.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function theBaselineCapitalisationAndPunctuationSurvive(): void {
  expect(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY).toContain('can NOT be empty');
  expect(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY).not.toContain('cannot');
  expect(SHARED_MESSAGES.ACCOUNT_ID_NOT_FOUND).toContain('NOT found');

  expect(SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N).toContain('Invalid value. ');
  expect(SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N).toContain('(Y/N)');
  expect(SHARED_MESSAGE_SOURCES.INVALID_VALUE_VALID_VALUES_ARE_Y_N).toContainEqual({
    file: PROGRAM,
    lines: [187],
  });

  expect(BILL_PAY_MESSAGES.UNABLE_TO_UPDATE_ACCOUNT).toContain('Update');
  expect(BILL_PAY_MESSAGE_LINES.UNABLE_TO_UPDATE_ACCOUNT).toContain(399);
  expect(BILL_PAY_MESSAGES.UNABLE_TO_LOOKUP_ACCOUNT).toContain('lookup');
  expect(BILL_PAY_MESSAGE_LINES.UNABLE_TO_LOOKUP_ACCOUNT).toContain(368);

  // WHY : Assumptions: the two are compared for INEQUALITY as well as for their own wording, because
  //       both begin `'Unable to '` and end `' Account...'` and differ only in the verb and its case
  //       — the shape most likely to be collapsed by an editor treating one as a typo of the other.
  expect(BILL_PAY_MESSAGES.UNABLE_TO_UPDATE_ACCOUNT).not.toBe(
    BILL_PAY_MESSAGES.UNABLE_TO_LOOKUP_ACCOUNT,
  );
}

/**
 * A blank account entry is refused in the reference's own words and reaches no service.
 *
 * Assumptions: the refusal is answered LOCALLY, because the reference answers it before it reaches a
 * file. `app/cbl/COBIL00C.cbl` L159-L164 tests the entry against spaces and low-values FIRST — ahead
 * of the confirmation evaluate at L173 — and sends the map immediately, so no dataset is read. The
 * service validates the same condition independently, so the local check is a duplicate by design
 * rather than the only guard.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aBlankEntryIsRefusedWithoutReachingTheService(): Promise<void> {
  const { payAccountBalanceInFull } = await import('../api/transactions');
  const { user } = await mountBillPay();

  await user.keyboard('{Enter}');

  // WHY : Assumptions: the sentence is asserted on the BAND specifically rather than found anywhere
  //       in the document, because this one turn paints it TWICE — once on the row-23 line and once
  //       as the marked field's help text, deliberately, so that the field marker and the message
  //       line carry one wording between them. An unscoped query for it matches both, and the field
  //       copy is asserted in the field-error suite where it belongs.
  await waitForBandToRead(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY);
  expect(vi.mocked(payAccountBalanceInFull)).not.toHaveBeenCalled();
}

/**
 * An identifier this mapset does not advertise paints the shared invalid-key sentence.
 *
 * Assumptions: the sentence is the shared one and not a sentence of this program's own.
 * `app/cbl/COBIL00C.cbl` L138-L141 is the `WHEN OTHER` arm of its `EVALUATE EIBAID`, and it moves
 * `CCDA-MSG-INVALID-KEY` — declared `PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L20-L21 — into the message
 * and re-sends the map. This screen is one of the fourteen programs that emit it.
 *
 * Assumptions: the sentence is trimmed for the comparison because the band trims what it renders.
 * The catalog stores the literal unpadded at 49 characters against a declared width of 50, and
 * `normaliseMessageBandValue` in `ui/src/messages/messages.ts` trims the value on the way to the
 * band — the padding is a field width, not content. The declared width is asserted separately so
 * that the 50 is recorded rather than lost to the trim.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anUnadvertisedIdentifierPaintsTheSharedRefusal(): Promise<void> {
  const { user } = await mountBillPay();

  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(COMMON_MESSAGES.INVALID_KEY.text).toBe(INVALID_KEY_PRESSED);

  await pressPfKey(user, 'PFK05');

  await waitForBandToRead(INVALID_KEY_PRESSED);
}

/**
 * A written payment is reported with both of the spaces its two literals contribute.
 *
 * Assumptions: the sentence carries a DOUBLE space between its halves and both are deliberate.
 * `app/cbl/COBIL00C.cbl` L527-L531 concatenates `'Payment successful. '`, which ends with a space,
 * with `' Your Transaction ID is '`, which begins with one, so the rendered text holds both. The
 * expected value is composed through the screen's own template helper, so the case cannot pass by
 * agreeing with a sentence this file wrote.
 *
 * Assumptions: `expectVerbatimMessage` is the query used rather than a plain text match, because its
 * normaliser is configured not to collapse internal whitespace. Testing Library's default collapses
 * a run of spaces to one, which would accept a screen that emitted a single space here — the exact
 * regression the doubled space is at risk of.
 *
 * Assumptions: the entry and the balance are both blank afterwards, because L523-L532 performs
 * `INITIALIZE-ALL-FIELDS` at L524 BEFORE composing the sentence at L527. So a successful payment
 * leaves only the sentence on screen.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aWrittenPaymentIsReportedVerbatim(): Promise<void> {
  const { paymentSuccessMessage } = await import('../screens/billPay');
  const transactionId = '0000000000000042';

  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  await answerWith(writtenPaymentOf(transactionId));
  await answerInDialogue(user, 'Y');

  await waitForBandToRead(paymentSuccessMessage(transactionId));
  expectVerbatimMessage(paymentSuccessMessage(transactionId));

  expect(accountEntry().value).toBe('');
  expect(paintedBalance()).toBeEmptyDOMElement();
}

/**
 * A failed lookup and a failed payment are reported with the wording each step owns.
 *
 * Assumptions: the two fallbacks DIFFER because the reference has different wording for the two
 * steps. A lookup that cannot complete is L368's `'Unable to lookup Account...'`; a payment that
 * cannot be written is L543's `'Unable to Add Bill pay Transaction...'`. Using one for both would
 * tell an operator whose payment failed that the lookup did, which is a different fault.
 *
 * Assumptions: a rejection that is not one of the client's normalised failures still paints a
 * baseline sentence rather than a runtime message. An unexpected throw means the request did not
 * complete, which is the condition those sentences name, and surfacing an error's own text would put
 * words on the row-23 line that no line of the reference holds.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function eachFailedStepIsReportedInItsOwnWords(): Promise<void> {
  await refuseWithATransportThatDidNotComplete();
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.UNABLE_TO_LOOKUP_ACCOUNT);
  expectVerbatimMessage(BILL_PAY_MESSAGES.UNABLE_TO_LOOKUP_ACCOUNT);

  // WHY : Assumptions: the paying turn is reached by first letting the service offer the payment and
  //       then failing the write, because the two fallbacks are selected by WHICH step failed and a
  //       case that only ever failed the lookup could not tell them apart.
  await answerWith(payableBalanceOf('1234.56'));
  await user.click(legendControl(BILL_PAY_KEY_LABELS.ENTER));
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  await refuseWithATransportThatDidNotComplete();
  await answerInDialogue(user, 'Y');

  await waitForBandToRead(BILL_PAY_MESSAGES.UNABLE_TO_ADD_BILL_PAY_TRANSACTION);
  expectVerbatimMessage(BILL_PAY_MESSAGES.UNABLE_TO_ADD_BILL_PAY_TRANSACTION);
}

/**
 * The three transaction-shaping constants are record data and are never painted on the screen.
 *
 * Assumptions: `app/cbl/COBIL00C.cbl` stamps the payment row with `'POS TERM'` at L222,
 * `'BILL PAYMENT - ONLINE'` at L223 and `'BILL PAYMENT'` at L227 — the source, the description and
 * the merchant name on the `TRAN-RECORD` it writes. They are values on a record, not text on a map,
 * and `services/transaction-service` carries them now, so a screen that rendered any of them would
 * be showing an operator a field the terminal never showed.
 *
 * Assumptions: the upper-case `'BILL PAYMENT'` merchant name is NOT the row-4 caption, even though
 * the two read alike. The caption is `'Bill Payment'`, mixed case, `LENGTH=12` at
 * `app/bms/COBIL00.bms` L75-L79, and the catalog keeps them as separate entries for that reason. The
 * case asserts the caption is present and the merchant name is absent, which is only meaningful
 * because the two differ in case.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theTransactionShapingConstantsAreNeverPainted(): Promise<void> {
  expect(BILL_PAY_MESSAGE_LINES.POS_TERM).toContain(222);
  expect(BILL_PAY_MESSAGE_LINES.BILL_PAYMENT_ONLINE).toContain(223);
  expect(BILL_PAY_MESSAGE_LINES.BILL_PAYMENT).toContain(227);

  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  expect(screen.queryByText(BILL_PAY_MESSAGES.POS_TERM)).toBeNull();
  expect(screen.queryByText(BILL_PAY_MESSAGES.BILL_PAYMENT_ONLINE)).toBeNull();
  expect(screen.queryByText(BILL_PAY_MESSAGES.BILL_PAYMENT)).toBeNull();
  expect(screen.getByRole('heading', { name: BILL_PAY_TITLE })).toBeInTheDocument();
}

/**
 * Registers the message-text cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function messageTextCases(): void {
  it('keeps both spellings of the account concept', bothSpellingsOfTheAccountConceptSurvive);
  it(
    'keeps the baseline capitalisation and punctuation',
    theBaselineCapitalisationAndPunctuationSurvive,
  );
  it(
    'refuses a blank entry without reaching the service',
    aBlankEntryIsRefusedWithoutReachingTheService,
  );
  it(
    'paints the shared refusal for an unadvertised key',
    anUnadvertisedIdentifierPaintsTheSharedRefusal,
  );
  it('reports a written payment with both of its spaces', aWrittenPaymentIsReportedVerbatim);
  it('reports each failed step in its own words', eachFailedStepIsReportedInItsOwnWords);
  it(
    'never paints the transaction-shaping constants',
    theTransactionShapingConstantsAreNeverPainted,
  );
}

describe('BillPayScreen message text', messageTextCases);

/**
 * Derives the CSS custom-property reference a design token resolves to.
 *
 * Assumptions: the design system emits each token as a kebab-cased custom property, so
 * `fontFamilyCode` becomes `font-family-code`, and this helper derives that name from the token
 * rather than writing the variable out. The prefix is deliberately NOT derived: only the token's own
 * segment is asserted, so the case does not also become an assertion about how the provider is
 * configured to prefix its variables.
 *
 * Alternatives Considered: asserting the resolved font stack instead. Rejected on two grounds — jsdom
 * has no layout engine, so a custom property is not resolved to a value there, and AAP section 0.3.2
 * admits no literal design value in this tree, so a font name in this file would be exactly the
 * hard-coded value the rule forbids.
 * @param {string} token - The design-system token name, from `ui/src/theme/tokens.ts`.
 * @returns {string} The kebab-cased custom-property segment that token resolves to.
 */
function customPropertySegmentOf(token: string): string {
  return token.replaceAll(
    /[A-Z]/gu,
    /**
     * Rewrites one capital into the hyphenated form a custom-property name uses.
     * @param {string} upper - The matched capital letter.
     * @returns {string} A hyphen followed by its lower-case form.
     */
    (upper: string): string => `-${upper.toLowerCase()}`,
  );
}

/**
 * The balance is carried and rendered as text, and never through a numeric conversion.
 *
 * Assumptions: `ACCT-CURR-BAL` is `PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L7 — a zoned-decimal
 * display field with a sign overpunch — so it is exact fixed point at the source and must stay exact
 * at every hop. AAP rule T3 fixes that as `NUMERIC(12,2)` in SQL, `BigDecimal` at scale two with
 * `HALF_UP` in Java, and a JSON STRING on the wire.
 *
 * Trade-offs: money is transported as a string rather than as a JSON number, and the mechanism is
 * specific: a JSON number is parsed into an IEEE-754 double by essentially every client, and a
 * double cannot represent most scale-two decimals exactly — so the exactness that `NUMERIC(12,2)`
 * and `BigDecimal` preserve would be discarded at the boundary the operator actually reads. This is
 * the highest-risk area in the migration because the failure is SILENT: it produces plausible
 * numbers that are wrong, and no screen shows anything unusual.
 *
 * Assumptions: the case proves the absence of a conversion by feeding a figure a conversion would
 * damage. `'8888888888.88'` needs more significant digits than a double carries exactly, so a
 * rendering that went through one would come back altered; the assertion is that every digit
 * survives in the order the service sent them.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theBalanceIsCarriedAsTextThroughout(): Promise<void> {
  const wireBalance = '8888888888.88';
  await answerWith(payableBalanceOf(wireBalance));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  // WHY : Assumptions: the rendered figure is `'+8888888888.88'` — the sign position the picture
  //       `+9999999999.99` always emits, then the ten integer positions unchanged, then the point and
  //       both decimal positions. Every digit of the wire value appears in order, which is what a
  //       conversion through a binary float would not guarantee.
  expect(paintedBalance().textContent).toBe('+8888888888.88');
  expect(typeof paintedBalance().textContent).toBe('string');

  // WHY : Assumptions: the mask zero-PADS rather than blanking, because the picture's positions are
  //       `9` and not `Z`. That single character is the whole difference between this screen's
  //       presentation and the account screens', whose `+ZZZ,ZZZ,ZZZ.99` suppresses leading zeros and
  //       groups with commas — so a small balance here paints its full width.
  await answerWith(payableBalanceOf('1.05'));
  await user.click(legendControl(BILL_PAY_KEY_LABELS.ENTER));
  await waitFor(
    /**
     * Waits for the second turn to repaint the balance.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(paintedBalance().textContent).toBe('+0000000001.05');
    },
  );
}

/**
 * The balance is painted in the fixed-pitch token so its columns align as they did on the terminal.
 *
 * Assumptions: the 3270 cell grid gave every numeric field one column per character without any font
 * being chosen, and a proportional face in a browser would lose that alignment. AAP section 0.3.3
 * maps fixed-pitch money and identifier columns onto the design system's code-font token, and
 * `ui/src/theme/tokens.ts` names it as `TYPOGRAPHY_TOKENS.fixedPitchData` so a consumer selects a
 * semantic use rather than repeating the system's own token name.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theBalanceIsPaintedInTheFixedPitchToken(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  const painted = paintedBalance();

  expect(TYPOGRAPHY_TOKENS.fixedPitchData).toBe('fontFamilyCode');
  expect(painted.style.fontFamily).toContain(
    customPropertySegmentOf(TYPOGRAPHY_TOKENS.fixedPitchData),
  );
}

/**
 * A zero balance reaches the nothing-to-pay advisory and withholds the payment control.
 *
 * Assumptions: `app/cbl/COBIL00C.cbl` L198 tests `IF ACCT-CURR-BAL <= ZEROS`, so the guard is
 * INCLUSIVE of zero, and L200-L204 answers it with `'You have nothing to pay...'` and puts the cursor
 * back on the account field rather than on the confirmation. The comparison is an exact fixed-point
 * one against a zoned-decimal display field, not a float compare: `'0.00'` is zero at scale two and
 * `'0.01'` is not, and there is no rounding step between them at which the two could be confused.
 *
 * ⚠️ Assumptions: the screen does not RE-DERIVE that comparison and is not expected to. It adopts the
 * service's decision by recognising which sentence came back — documented in
 * `ui/src/screens/billPay/index.tsx` as the deliberate rejection of testing the sign locally, because
 * a second implementation of one rule can only agree or disagree with the first, and when it
 * disagreed the screen would offer a payment the service then refused. What this case therefore
 * asserts is the property that matters at this layer: the screen classifies the outcome without
 * converting the balance, and it withholds the payment control on the non-positive answer.
 *
 * Assumptions: an off-contract `'0'` is included alongside `'0.00'` because both are zero and the
 * screen must treat them alike. The service publishes money through a plain-string form that always
 * carries the point and both decimal positions, so `'0'` cannot arrive in practice; a screen that
 * decided payability by converting would nonetheless treat the two identically, and one that pattern-
 * matched the wire form might not — which is why the case drives both.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aZeroBalanceReachesTheNothingToPayAdvisory(): Promise<void> {
  await answerWith(nothingToPayOn('0.00'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.YOU_HAVE_NOTHING_TO_PAY);
  expectVerbatimMessage(BILL_PAY_MESSAGES.YOU_HAVE_NOTHING_TO_PAY);
  expect(BILL_PAY_MESSAGE_LINES.YOU_HAVE_NOTHING_TO_PAY).toContain(201);

  expect(paintedBalance().textContent).toBe('+0000000000.00');
  expect(confirmationTrigger()).toBeDisabled();

  // WHY : Assumptions: the cursor returns to the account entry on this turn and NOT to the
  //       confirmation control, which is the reference's own split — L203 moves `-1` to `ACTIDINL`
  //       here, where L239 moves it to `CONFIRML` beside the confirmation prompt.
  await waitFor(
    /**
     * Waits for the recorded cursor move to be applied.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(accountEntry()).toHaveFocus();
    },
  );

  await answerWith(nothingToPayOn('0'));
  await user.click(legendControl(BILL_PAY_KEY_LABELS.ENTER));
  await waitForBandToRead(BILL_PAY_MESSAGES.YOU_HAVE_NOTHING_TO_PAY);
  expect(confirmationTrigger()).toBeDisabled();

  // WHY : Assumptions: an off-contract value is painted UNCHANGED rather than coerced into the mask,
  //       which is the same discipline `ui/src/format/money.ts` records for a figure its own mask
  //       cannot render. Printing the text keeps the amount truthful in the one case where its
  //       presentation cannot be; padding or parsing it would invent a figure the service never sent.
  expect(paintedBalance().textContent).toBe('0');
}

/**
 * One cent is a payable balance, so the advisory does not fire and the payment is offered.
 *
 * Assumptions: this is the other side of the inclusive comparison at `app/cbl/COBIL00C.cbl` L198 and
 * the reason the boundary is asserted from both directions. `'0.01'` is greater than zero at scale
 * two, so the reference reaches L236-L240 of that program and paints the confirmation prompt instead
 * of the advisory — and a screen that decided payability by rounding, truncating or converting could
 * easily place this value on the wrong side. The painted form is the mask of `WS-CURR-BAL
 * PIC +9999999999.99` at L56, whose `9` positions zero-pad rather than suppress.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function oneCentIsPayable(): Promise<void> {
  await answerWith(payableBalanceOf('0.01'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  expect(messageBand().textContent).not.toBe(BILL_PAY_MESSAGES.YOU_HAVE_NOTHING_TO_PAY);
  expect(paintedBalance().textContent).toBe('+0000000000.01');
  await waitFor(
    /**
     * Waits for the payment control to be offered.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationTrigger()).toBeEnabled();
    },
  );
}

/**
 * A credit balance reaches the same advisory as a zero one.
 *
 * Assumptions: `ACCT-CURR-BAL` is a SIGNED field, `PIC S9(10)V99` at `app/cpy/CVACT01Y.cpy` L7, so a
 * credit balance is negative in this record's sign convention and the `<= ZEROS` test at
 * `app/cbl/COBIL00C.cbl` L198 catches it alongside zero. Both therefore reach the same advisory,
 * which is why the reference needs no separate wording for a customer in credit.
 *
 * Assumptions: the sign position of the mask emits `'-'` here rather than `'+'`, because the leading
 * `+` of `WS-CURR-BAL PIC +9999999999.99` at `app/cbl/COBIL00C.cbl` L56 is a FIXED position that
 * prints the sign it is given — one character either way — rather than a conditional one that appears
 * only for a positive value.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aCreditBalanceReachesTheSameAdvisory(): Promise<void> {
  await answerWith(nothingToPayOn('-25.00'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.YOU_HAVE_NOTHING_TO_PAY);

  expect(paintedBalance().textContent).toBe('-0000000025.00');
  expect(confirmationTrigger()).toBeDisabled();
}

/**
 * Editing the account entry discards the balance the previous turn reported.
 *
 * Assumptions: the figure on screen describes the account that WAS looked up, so leaving it in place
 * while the entry changes would let an operator confirm a payment against a balance belonging to a
 * different account. The reference cannot produce that state at all — its balance field is repainted
 * by the same turn that reads the entry — so discarding it is what reproduces the terminal's
 * behaviour rather than an added precaution.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function editingTheEntryDiscardsTheReportedBalance(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitFor(
    /**
     * Waits for the payment control to be offered.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationTrigger()).toBeEnabled();
    },
  );

  // WHY : Assumptions: the edit is a CLEAR rather than an appended digit, and the reason is the width
  //       contract asserted above: the entry already holds its eleven declared positions, so a twelfth
  //       keystroke is dropped by `maxLength`, no change event is raised and a case that appended one
  //       would assert nothing at all. Clearing is an edit that the field can actually receive.
  await user.clear(accountEntry());

  expect(paintedBalance()).toBeEmptyDOMElement();
  expect(confirmationTrigger()).toBeDisabled();
}

/**
 * Registers the balance and zero-guard cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function balanceCases(): void {
  it('carries the balance as text with no numeric conversion', theBalanceIsCarriedAsTextThroughout);
  it('paints the balance in the fixed-pitch token', theBalanceIsPaintedInTheFixedPitchToken);
  it(
    'reaches the nothing-to-pay advisory for a zero balance',
    aZeroBalanceReachesTheNothingToPayAdvisory,
  );
  it('treats one cent as payable', oneCentIsPayable);
  it('reaches the same advisory for a credit balance', aCreditBalanceReachesTheSameAdvisory);
  it(
    'discards the reported balance when the entry is edited',
    editingTheEntryDiscardsTheReportedBalance,
  );
}

describe('BillPayScreen balance handling', balanceCases);

/**
 * The row-24 legend advertises exactly the three captions the mapset paints, spacing included.
 *
 * Assumptions: the mapset paints ONE `LENGTH=33` field in `COLOR=YELLOW` at
 * `app/bms/COBIL00.bms` L131-L135, reading `ENTER=Continue  F3=Back  F4=Clear`, whose three parts are
 * separated by TWO spaces: 14 + 2 + 7 + 2 + 8 = 33. The legend is reconstructed here from the three
 * captions the catalog owns and compared against that arithmetic, so the case asserts the spacing
 * rather than only the captions — a single-space join would produce a 31-character legend and fail.
 *
 * Assumptions: the captions have TWO owners and the join happens here for that reason. ENTER reads
 * `Continue` on this mapset where others paint `Process`, and PF3 reads `Back` where nine others
 * paint `Exit`, so both are screen-owned in `BILL_PAY_KEY_LABELS`; `F4=Clear` is byte-identical
 * everywhere it appears and is published by `ui/src/layout/PfKeyBar.tsx`.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theLegendAdvertisesExactlyThreeCaptions(): Promise<void> {
  await mountBillPay();

  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const captions = within(legend)
    .getAllByRole('button')
    .map(
      /**
       * Reads one legend control's caption.
       * @param {HTMLElement} control - One rendered legend control.
       * @returns {string} Its text content, or the empty string if it has none.
       */
      (control: HTMLElement): string => control.textContent ?? '',
    );

  expect(captions).toEqual([
    BILL_PAY_KEY_LABELS.ENTER,
    BILL_PAY_KEY_LABELS.PFK03,
    UNIFORM_PF_KEY_LABELS.PFK04,
  ]);

  const painted = captions.join('  ');
  expect(painted).toBe('ENTER=Continue  F3=Back  F4=Clear');
  expect(painted).toHaveLength(33);
}

/**
 * Neither the legend nor the key table carries PF5, PF7, PF8 or PF12.
 *
 * ⚠️ Assumptions: a screen that moves money and has no Save key is counter-intuitive and is
 * nonetheless the baseline's own design. `app/cbl/COBIL00C.cbl` L125-L142 dispatches `DFHENTER`,
 * `DFHPF3` and `DFHPF4` and sends every other identifier to `WHEN OTHER`, and the row-24 legend
 * advertises the same three — so there is nothing to save with, because the confirm-then-answer pair
 * IS the commit. PF7 and PF8 are the paging keys of the browse mapsets and PF12 the cancel key of
 * three others; none belongs here.
 *
 * Assumptions: absence is asserted TWICE, once per channel, because the two fail differently. A key
 * missing from the legend is invisible to a pointer; a key missing from the handler table is refused
 * by the keyboard, and `ui/src/layout/usePfKeys.ts` answers an unmapped identifier through the
 * screen's invalid-key sink. Checking only the legend would pass on a screen that had quietly bound
 * PF5 without advertising it — which is the more dangerous of the two, since it would be a working
 * money key nobody could see.
 *
 * Trade-offs: `PfKeyBar` renders a control per LABELLED binding, so a binding with a blank label
 * would be absent from the legend while remaining live. That is exactly why the keyboard half of this
 * case dispatches the identifiers rather than inspecting the descriptor list.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theUnadvertisedIdentifiersAreBoundToNothing(): Promise<void> {
  const { payAccountBalanceInFull } = await import('../api/transactions');
  const { user } = await mountBillPay();
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });

  expect(within(legend).queryByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK07 })).toBeNull();
  expect(within(legend).queryByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK08 })).toBeNull();

  for (const aid of UNADVERTISED_AIDS) {
    await pressPfKey(user, aid);
    await waitForBandToRead(INVALID_KEY_PRESSED);
  }

  // WHY : Assumptions: none of the four reached the service, which is the assertion that matters for
  //       PF5 specifically — an unbound key that nonetheless submitted a turn would move money on a
  //       keystroke the reference discards.
  expect(vi.mocked(payAccountBalanceInFull)).not.toHaveBeenCalled();
}

/**
 * Enter takes the reporting turn from the keyboard and from the legend control alike.
 *
 * Assumptions: both channels are driven because the contract is a KEYBOARD contract and the legend
 * control is additive. The 3270 original had no pointer, so a case that only clicked would pass in
 * full while every keyboard binding on the screen was broken; a case that only pressed would pass
 * while the visible control did nothing. `ui/src/test/setup.ts` records the same reasoning on
 * `pressPfKey`.
 *
 * Alternatives Considered: asserting that the two paths share one handler by inspecting the
 * descriptor list the screen publishes. Rejected because the descriptor list proves the wiring and
 * not the dispatch — the frame's control and the document's keydown listener are two different
 * routes into it, and only running both shows that both arrive.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function enterTakesTheTurnFromBothChannels(): Promise<void> {
  const { payAccountBalanceInFull } = await import('../api/transactions');
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
  expect(vi.mocked(payAccountBalanceInFull)).toHaveBeenCalledTimes(1);

  await user.click(legendControl(BILL_PAY_KEY_LABELS.ENTER));
  await waitFor(
    /**
     * Waits for the second turn to be dispatched.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(vi.mocked(payAccountBalanceInFull)).toHaveBeenCalledTimes(2);
    },
  );

  // WHY : Assumptions: the two turns carry the SAME request, which is what shows the legend control
  //       and the key press ran one handler rather than two that merely look alike.
  expect(await requestOfCall(1)).toEqual(await requestOfCall(0));
}

/**
 * Enter is rendered with the primary emphasis and the other two with the default.
 *
 * Assumptions: the pairing is the design system's rather than the mapset's. AAP section 0.3.2 maps the
 * action keys onto `Button` with `type="primary"` for ENTER and PF5 and `type="default"` for PF3, PF4
 * and PF12, and `ui/src/layout/PfKeyBar.tsx` publishes that decision as `PRIMARY_ACTION_AIDS`. The
 * 3270 expressed emphasis with `ATTRB=BRT`, which this tree resolves to font weight rather than to a
 * button variant, so terminal brightness and button emphasis are independent decisions and only the
 * latter is asserted here.
 *
 * Assumptions: the variant is read off the rendered class rather than from a prop, because a prop is
 * not observable from a test and the class is what a browser actually styles from.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theEmphasisMatchesTheDesignSystemMapping(): Promise<void> {
  await mountBillPay();

  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER).className).toContain('ant-btn-primary');
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03).className).toContain('ant-btn-default');
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04).className).toContain('ant-btn-default');
}

/**
 * PF4 clears the account entry, the reported balance, the message and the confirmation state.
 *
 * Assumptions: all four, because `CLEAR-CURRENT-SCREEN` at `app/cbl/COBIL00C.cbl` L552-L555 performs
 * `INITIALIZE-ALL-FIELDS`, and that paragraph at L558-L566 moves spaces into `ACTIDINI`, `CURBALI`,
 * `CONFIRMI` and `WS-MESSAGE` together and returns the cursor to the account field. Clearing only the
 * entry would leave a balance on screen that no longer describes anything the operator typed.
 *
 * Assumptions: the confirmation's cleared state is observed as the trigger becoming unavailable
 * again, because the single-character field it replaces has no separate control to inspect — the
 * dialogue exists only while it is open, and what `MOVE SPACES TO CONFIRMI` leaves behind is a screen
 * on which no payment is offered.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theClearKeyReinitialisesEveryField(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
  await waitFor(
    /**
     * Waits for the payment control to be offered.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationTrigger()).toBeEnabled();
    },
  );

  await pressPfKey(user, 'PFK04');

  expect(accountEntry().value).toBe('');
  expect(paintedBalance()).toBeEmptyDOMElement();
  expect(messageBand().textContent).toBe('');
  expect(confirmationTrigger()).toBeDisabled();

  // WHY : Assumptions: the legend control clears just as the key does, asserted on a second populated
  //       turn rather than on the already-cleared screen — clearing an empty screen would pass whether
  //       the control was wired or not.
  await answerWith(payableBalanceOf('1234.56'));
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  await user.click(legendControl(UNIFORM_PF_KEY_LABELS.PFK04));

  expect(accountEntry().value).toBe('');
  expect(paintedBalance()).toBeEmptyDOMElement();
  expect(messageBand().textContent).toBe('');
}

/**
 * PF3 returns to the main menu from the keyboard and from the legend control alike.
 *
 * Assumptions: the destination is the main menu because `app/cbl/COBIL00C.cbl` L128-L135 returns to
 * `CDEMO-FROM-PROGRAM` and substitutes `'COMEN01C'` when that field is blank or low-values, which is
 * the state a screen entered directly is in. AAP rule T5 maps `EXEC CICS XCTL` to a client-side route
 * change, so the migrated form of that `XCTL` is a navigation and not a request.
 *
 * Assumptions: no server-side "next program" field takes part. The reference carried the destination
 * in `CDEMO-TO-PROGRAM` inside the passed session struct; AAP section 0.7.1 removes that struct
 * entirely, so the key resolves its own destination and the transport is not called at all — which
 * this case asserts alongside the address.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theBackKeyReturnsToTheMainMenu(): Promise<void> {
  const { payAccountBalanceInFull } = await import('../api/transactions');
  const { user } = await mountBillPayWithAddressProbe();

  expect(screen.getByTestId('probed-address')).toHaveTextContent(BILL_PAY_PATH);

  await pressPfKey(user, 'PFK03');

  await waitFor(
    /**
     * Waits for the navigation to be applied.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(screen.getByTestId('probed-address')).toHaveTextContent(MAIN_MENU_DESTINATION);
    },
  );
  expect(vi.mocked(payAccountBalanceInFull)).not.toHaveBeenCalled();
}

/**
 * The legend's back control reaches the same destination the key does.
 *
 * Assumptions: this is a separate case rather than a second half of the one above, because the two
 * navigations cannot be observed in one render — the first leaves the address the second would have
 * to start from. Trade-offs: two mounts instead of one, in exchange for each assertion starting from
 * the address the reference starts from.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theBackControlReturnsToTheMainMenu(): Promise<void> {
  const { user } = await mountBillPayWithAddressProbe();

  await user.click(legendControl(BILL_PAY_KEY_LABELS.PFK03));

  await waitFor(
    /**
     * Waits for the navigation to be applied.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(screen.getByTestId('probed-address')).toHaveTextContent(MAIN_MENU_DESTINATION);
    },
  );
}

/**
 * Registers the PF-key cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function pfKeyCases(): void {
  it(
    'advertises exactly the three captions the mapset paints',
    theLegendAdvertisesExactlyThreeCaptions,
  );
  it('binds no PF5, PF7, PF8 or PF12', theUnadvertisedIdentifiersAreBoundToNothing);
  it(
    'takes the reporting turn from the key and from the control',
    enterTakesTheTurnFromBothChannels,
  );
  it('emphasises Enter and defaults the other two', theEmphasisMatchesTheDesignSystemMapping);
  it('reinitialises every field on the clear key', theClearKeyReinitialisesEveryField);
  it('returns to the main menu on the back key', theBackKeyReturnsToTheMainMenu);
  it('returns to the main menu on the back control', theBackControlReturnsToTheMainMenu);
}

describe('BillPayScreen function keys', pfKeyCases);

/**
 * Payment is offered only once a balance has been reported, and only behind a confirmation.
 *
 * Assumptions: AAP section 0.4.1.4 composes this screen from a form and a confirmation dialogue, and
 * the dialogue is the "confirmation before destructive action" mapping — which a balance-affecting
 * write is, since it moves real money and cannot be undone from this screen. It replaces the 3270
 * re-key-to-confirm convention that L237's prompt and L187's domain refusal together express.
 *
 * Assumptions: the dialogue's title is the mapset's own 53-character literal, trailing space and full
 * stop included. `'Do you want to pay your balance now. Please confirm: '` is `LENGTH=53` at
 * `app/bms/COBIL00.bms` L109-L114 as a BMS continuation across two lines, and it ends its first
 * sentence with a full stop rather than a question mark. Both read like transcription slips and
 * neither is corrected, so the literal is taken from the catalog and never retyped.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theConfirmationGatesThePayment(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  // WHY : Assumptions: the trigger starts UNAVAILABLE, because the reference reaches its confirmation
  //       prompt at L236-L240 only after a balance has been read — L208's `IF NOT ERR-FLG-ON` gate
  //       means no turn that failed or reported nothing to pay ever offers it.
  expect(confirmationTrigger()).toBeDisabled();
  expect(screen.queryByRole('tooltip')).toBeNull();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitFor(
    /**
     * Waits for the payment control to be offered.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationTrigger()).toBeEnabled();
    },
  );

  await user.click(confirmationTrigger());
  const dialogue = await screen.findByRole('tooltip');

  expect(
    within(dialogue).getByText(BILL_PAY_FIELD_LABELS.confirmPrompt.trim()),
  ).toBeInTheDocument();
}

/**
 * Declining the confirmation reaches no service and clears the screen.
 *
 * Assumptions: the decline is answered LOCALLY because the reference reaches no file either —
 * `app/cbl/COBIL00C.cbl` L178-L181 performs `CLEAR-CURRENT-SCREEN` and sets the error flag, which
 * suppresses every later sentence, so the turn is silent as well as requestless. Sending the answer
 * would spend a request to be told what is already known and would let an unrelated transport failure
 * paint an error on a turn the reference answers in silence.
 *
 * ⚠️ Assumptions: the call count is asserted against the count BEFORE the dialogue opened rather than
 * against zero, because the reporting turn that made the payment available is itself a call. Asserting
 * zero would be asserting that the balance was never looked up.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function decliningTheConfirmationReachesNoService(): Promise<void> {
  const { payAccountBalanceInFull } = await import('../api/transactions');
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
  expect(vi.mocked(payAccountBalanceInFull)).toHaveBeenCalledTimes(1);

  await answerInDialogue(user, 'N');

  await waitFor(
    /**
     * Waits for the declining answer to clear the screen.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(accountEntry().value).toBe('');
    },
  );
  expect(vi.mocked(payAccountBalanceInFull)).toHaveBeenCalledTimes(1);
  expect(paintedBalance()).toBeEmptyDOMElement();
  expect(messageBand().textContent).toBe('');
}

/**
 * Confirming submits the payment exactly once, carrying the confirming answer.
 *
 * ⚠️ Assumptions: "exactly once" is the assertion and not an incidental count. A double submission on
 * a money write is a real defect class rather than a hypothetical: the reference could not produce one
 * because a 3270 keyboard locked from the moment the map was sent until the region replied, so
 * nothing about the terminal had to guard against it, and a browser has to reconstruct that guard
 * explicitly.
 *
 * Assumptions: the payment is ONE request carrying the confirmation rather than a second write layered
 * on the report. `app/cbl/COBIL00C.cbl` L210-L235 performs the cross-reference read, the identifier
 * generation, the ledger write and the balance reduction inside a single CICS task, and the service
 * performs the equivalent inside one database transaction — so splitting it client-side would make a
 * partially applied payment observable, a state the baseline never exhibits.
 *
 * Assumptions: the answer is the upper-case `'Y'`, which is inside the set `EVALUATE CONFIRMI` admits
 * at L173-L176. The reference accepts either case there; sending the upper-case form keeps the request
 * inside the set the service's own confirmation validator admits as well.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function confirmingSubmitsExactlyOnePayment(): Promise<void> {
  const { payAccountBalanceInFull } = await import('../api/transactions');
  const { paymentSuccessMessage } = await import('../screens/billPay');
  const transactionId = '0000000000000101';

  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  await answerWith(writtenPaymentOf(transactionId));
  await answerInDialogue(user, 'Y');
  await waitForBandToRead(paymentSuccessMessage(transactionId));

  expect(vi.mocked(payAccountBalanceInFull)).toHaveBeenCalledTimes(2);
  expect(await requestOfCall(1)).toEqual({
    accountId: FIXTURE_ACCOUNT_ID,
    confirmation: 'Y',
  });

  // WHY : Assumptions: the payment control is withdrawn again afterwards, so the settled turn cannot
  //       be re-confirmed. `INITIALIZE-ALL-FIELDS` at L524 runs before the success sentence is composed
  //       at L527, which leaves the entry blank and nothing left to pay.
  expect(confirmationTrigger()).toBeDisabled();
}

/**
 * The reporting turn omits the confirmation member rather than sending it empty.
 *
 * Assumptions: absence and an explicit empty value are DIFFERENT to the service. The contract declares
 * the member optional and the service reads its absence the way the reference reads a blank `CONFIRMI`
 * at `app/cbl/COBIL00C.cbl` L182-L184 — read the account and report the balance without writing —
 * whereas a value it does not recognise is the L185-L190 refusal. `ui/tsconfig.json` sets
 * `exactOptionalPropertyTypes`, under which an explicit `undefined` and an absent member are different
 * types, so the distinction is enforced at compile time as well as asserted here.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theReportingTurnOmitsTheConfirmation(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  const request = await requestOfCall(0);
  expect(Object.keys(request)).toEqual(['accountId']);
  expect('confirmation' in request).toBe(false);
}

/**
 * The domain refusal is carried verbatim in the catalog and cannot be provoked from this screen.
 *
 * ⚠️ Refactoring Rationale: `'Invalid value. Valid values are (Y/N)...'` at `app/cbl/COBIL00C.cbl`
 * L187 is UNREACHABLE here, and that is a consequence of replacing the single-character field with a
 * dialogue rather than an omission. The field existed because a 3270 terminal had no modal, and the
 * program re-read the whole map to discover what had been typed into it; a dialogue asks the question
 * directly and can only send the two answers it offers, so the branch that rejected a third character
 * has nothing to reject.
 *
 * Assumptions: the sentence is kept anyway, for two reasons that are both live. `app/cbl/COTRN02C.cbl`
 * still emits it, and the service still validates the member independently — so a refusal carrying it
 * can still arrive, and `billPayFailure` renders whatever sentence the service sent rather than
 * substituting one of its own.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theDomainRefusalIsCarriedButUnreachable(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitFor(
    /**
     * Waits for the payment control to be offered.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationTrigger()).toBeEnabled();
    },
  );

  await user.click(confirmationTrigger());
  const dialogue = await screen.findByRole('tooltip');
  const answers = within(dialogue)
    .getAllByRole('button')
    .map(
      /**
       * Reads one answer control's caption.
       * @param {HTMLElement} control - One rendered answer control.
       * @returns {string} Its text content, or the empty string if it has none.
       */
      (control: HTMLElement): string => control.textContent ?? '',
    );

  // WHY : Assumptions: the two answers are exactly the two the reference's `EVALUATE CONFIRMI` accepts
  //       at L174 and L178, so no submission this dialogue can make reaches L185's `WHEN OTHER` arm.
  //       That, and not the absence of the sentence from the catalog, is why the refusal cannot appear.
  //       The pair is compared as a SET, sorted into a stable order, because the panel's document
  //       order puts the declining control first and that ordering is the design system's rather than
  //       this contract's. A copy is sorted rather than the array itself, so the query's own result is
  //       not mutated for any later reader.
  expect([...answers].sort()).toEqual(['N', 'Y']);
  expect(screen.queryByText(SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N)).toBeNull();
}

/**
 * Registers the confirmation-dialogue cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function confirmationCases(): void {
  it('offers payment only behind a confirmation', theConfirmationGatesThePayment);
  it(
    'reaches no service when the confirmation is declined',
    decliningTheConfirmationReachesNoService,
  );
  it('submits exactly one payment when confirmed', confirmingSubmitsExactlyOnePayment);
  it('omits the confirmation member on the reporting turn', theReportingTurnOmitsTheConfirmation);
  it(
    'carries the domain refusal without being able to provoke it',
    theDomainRefusalIsCarriedButUnreachable,
  );
}

describe('BillPayScreen confirmation', confirmationCases);

/**
 * An operator with no administrative claim can reach the screen and complete a payment.
 *
 * Assumptions: this screen is NOT administrative, and the copybook settles it.
 * `app/cpy/COMEN02Y.cpy` lists `'Bill Payment'` as main-menu option 10 with `'COBIL00C'` as its
 * program at L83 and its user-type FILLER at L84 set to `'U'` — and every one of the eleven populated
 * options is `'U'`, with not one `'A'` among them. So gating this route on the administrative claim
 * would lock out exactly the operators it exists for, and `ui/src/router.tsx` mounts it in the
 * guarded but non-administrative subtree for that reason.
 *
 * ⚠️ Assumptions: the session is established through the harness's identity helper and by no other
 * means. `ui/src/hooks/useAuth.ts` publishes NO setter for the groups or the user type and there is no
 * context provider anywhere, because authority derives solely from the signed `cognito:groups` claim —
 * so a test cannot grant itself a group, and one that could would be exercising a weaker path to
 * authority than production has. The helper mints a token carrying the group and drives the real
 * sign-on exchange, which is the only path there is.
 *
 * Refactoring Rationale: the reference carried `CDEMO-USER-TYPE PIC X(01)` with its `'A'`/`'U'`
 * condition names inside the session struct at `app/cpy/COCOM01Y.cpy` L26-L28 — storage the client
 * echoed back, so a client could in principle assert its own type. AAP section 0.7.1 replaces it with
 * a signed claim the client cannot assert. That is a security improvement rather than a port, and it
 * is why this case reads the authority from the session rather than passing it to the screen.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aNonAdminOperatorCanUseTheScreen(): Promise<void> {
  const { paymentSuccessMessage } = await import('../screens/billPay');
  const transactionId = '0000000000000777';

  const session = await seedSession({ groups: [CARDDEMO_USER_GROUP] });

  expect(session.result.current.groups).toContain(CARDDEMO_USER_GROUP);
  expect(session.result.current.groups).not.toContain(CARDDEMO_ADMIN_GROUP);
  expect(session.result.current.isAdmin).toBe(false);

  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  await answerWith(writtenPaymentOf(transactionId));
  await answerInDialogue(user, 'Y');

  await waitForBandToRead(paymentSuccessMessage(transactionId));
}

/**
 * The account identifier reaches the service as a request member and nothing else travels with it.
 *
 * Assumptions: AAP section 0.7.1 replaces `CDEMO-ACCT-ID PIC 9(11)` — the selection context the
 * reference carried in the passed session struct at `app/cpy/COCOM01Y.cpy` L38 — with a REST path or
 * query parameter, and this contract makes it a request member.
 *
 * Refactoring Rationale: the change is not cosmetic. The struct was storage the client echoed back, so
 * a turn's identity, its user type and its selection all arrived from the client together and could
 * not be authorized independently; a request member can be authorized per request against a signed
 * claim the same request carries. The assertion is therefore on the request's KEY SET and not merely
 * on the identifier's value: a member carrying an operator identifier or a remembered turn counter
 * would be the session struct reappearing on the wire.
 *
 * Assumptions: the identifier is the one the operator TYPED, which is what makes the request
 * self-describing. The reference could pre-fill the field from `CDEMO-CB00-TRN-SELECTED` at L116-L121,
 * and that path is dead — nothing in `app/` populates the group — so the screen reads no identifier
 * from its route and no case here supplies one.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theSelectionTravelsAsARequestMember(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  const reporting = await requestOfCall(0);
  expect(reporting.accountId).toBe(FIXTURE_ACCOUNT_ID);
  expect(Object.keys(reporting)).toEqual(['accountId']);

  const { paymentSuccessMessage } = await import('../screens/billPay');
  const transactionId = '0000000000000042';
  await answerWith(writtenPaymentOf(transactionId));
  await answerInDialogue(user, 'Y');

  // WHY : Assumptions: the settled turn is awaited through the SENTENCE it paints rather than through
  //       a call count, because the sentence is the observable an operator gets and it can only appear
  //       once the request has resolved. A count would be satisfied the moment the request left, which
  //       is before the member set under assertion has been recorded on a completed turn.
  await waitForBandToRead(paymentSuccessMessage(transactionId));

  const paying = await requestOfCall(1);
  // WHY : Assumptions: the member names are sorted before comparison because the ENUMERATION order of
  //       an object's own properties is not part of any contract here, while the member SET is exactly
  //       what this case is asserting. A copy is sorted so the enumeration result is left untouched.
  expect([...Object.keys(paying)].sort()).toEqual(['accountId', 'confirmation']);
  expect(paying.accountId).toBe(FIXTURE_ACCOUNT_ID);
}

/**
 * No card data is painted, and no card member crosses the boundary.
 *
 * Assumptions: this screen shows no card at all, which is the strongest available form of the masking
 * rule. `app/cbl/COBIL00C.cbl` L225 does move `XREF-CARD-NUM` into the transaction row it writes, but
 * that is a field on a RECORD and the mapset paints no card field anywhere among its 24 definitions —
 * so there is nothing here to mask, and the administrative exception that lets the card-detail endpoint
 * return a full number has no application to this screen.
 *
 * Assumptions: the verification value is absent by construction rather than by suppression.
 * `ui/src/api/types.ts` declares no such member on any shape this screen touches, so no code path
 * exists that could serialise one.
 *
 * Trade-offs: the assertion is a NEGATIVE on the rendered text rather than a positive on a masked
 * value, because a screen that renders no card number cannot demonstrate masking. The check is
 * nonetheless falsifiable: a card field added to this screen would put the word on it.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function noCardDataIsPaintedOrSent(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  const body = screen.getByRole('main');
  expect(within(body).queryByText(/card/iu)).toBeNull();

  const request = await requestOfCall(0);
  expect(Object.keys(request)).toEqual(['accountId']);
}

/**
 * Registers the session, routing and exposure cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function sessionAndExposureCases(): void {
  it(
    'lets an operator with no administrative claim pay a balance',
    aNonAdminOperatorCanUseTheScreen,
  );
  it(
    'sends the selection as a request member and nothing more',
    theSelectionTravelsAsARequestMember,
  );
  it('paints and sends no card data', noCardDataIsPaintedOrSent);
}

describe('BillPayScreen session and exposure', sessionAndExposureCases);

/**
 * The class the design system's form row carries while it is in the refused state.
 *
 * Assumptions: `validateStatus="error"` is a PROP, and a prop is not observable from a rendered tree,
 * so the state has to be read from the class the design system emits for it. AAP section 0.3.2 maps
 * the templated field highlight onto `Form.Item validateStatus="error"` with `help` text, and this
 * class is that mapping's rendered form — so a case asserting it is asserting the mapping and not an
 * implementation detail of this file.
 *
 * Trade-offs: reading a library class couples these cases to the design system's rendered output,
 * which a major version could rename. That is accepted because the alternative — asserting nothing
 * about the marked state, or asserting only the help text — would let a field lose its mark while its
 * sentence still rendered, which is exactly the regression the reference's `MOVE -1 TO ACTIDINL` at
 * `app/cbl/COBIL00C.cbl` L163 exists to prevent.
 */
const ERROR_ROW_CLASS = 'ant-form-item-has-error';

/**
 * Locates the form row that surrounds the account entry.
 *
 * Assumptions: the row is reached from the control rather than by a class query, because the control
 * is the element a case has a stable handle on and the row is whatever wraps it. The design system's
 * own row carries the error state, so the row is what has to be inspected for it.
 * @returns {HTMLElement} The form row.
 * @throws {Error} If the control is not inside a form row, which would mean the field is no longer
 *   composed through the design system's form primitive at all.
 */
function accountFormRow(): HTMLElement {
  const row = accountEntry().closest('.ant-form-item');
  if (!(row instanceof HTMLElement)) {
    throw new Error('the account entry is not inside a design-system form row');
  }
  return row;
}

/**
 * A refused account entry is marked, explained, and announced to assistive software.
 *
 * Assumptions: the target of `app/cpy/CSSETATY.cpy`'s contract is an error state on the form row plus
 * help text, and both halves are asserted because they serve different readers — the state is the
 * visual signal and the help text is what a screen reader announces through the control's own
 * description.
 *
 * Refactoring Rationale: the COBOL gates its field highlight on `CDEMO-PGM-REENTER`, the
 * pseudo-conversational turn counter declared at `app/cpy/COCOM01Y.cpy` L29-L31, so a first-entry turn
 * highlighted nothing however invalid the field was. AAP section 0.7.1 removes that discriminator
 * entirely — a stateless handler has no turn to remember — so the highlight is driven purely by the
 * response body, and there is no first-entry state in which a marked field would go unmarked.
 *
 * Assumptions: the row-23 sentence and the field's help text are the SAME sentence, deliberately, so
 * the marker and the message line carry one wording between them. That is why this case queries the
 * help text through the row rather than through the document.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aRefusedEntryIsMarkedAndExplained(): Promise<void> {
  const { user } = await mountBillPay();

  expect(accountFormRow().className).not.toContain(ERROR_ROW_CLASS);

  await user.keyboard('{Enter}');
  await waitForBandToRead(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY);

  expect(accountFormRow().className).toContain(ERROR_ROW_CLASS);
  expect(
    within(accountFormRow()).getByText(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY),
  ).toBeInTheDocument();
  expect(accountEntry()).toHaveAttribute('aria-invalid', 'true');
  expect(accountEntry()).toHaveAccessibleDescription(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY);
}

/**
 * A field the response does not name carries no error state.
 *
 * Assumptions: the discrimination is per FIELD and comes from the response body alone, so a turn that
 * failed without attributing the failure to a field must leave every field unmarked while still
 * painting the row-23 sentence. Marking the entry on such a turn would tell an operator to correct a
 * value that was not the problem.
 *
 * Assumptions: a failure carrying no attributed field is the realistic shape of this case here. The
 * screen recognises an attributed refusal through `isApiRequestError`, whose class lives in
 * `ui/src/api/client.ts` — outside this file's declared dependency set — so the attributed variant is
 * asserted at the mapping layer in the case below, and the RENDERED negative is driven through the
 * unattributed arm, which produces exactly the state under assertion: an empty refusal array.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anUnattributedFailureMarksNoField(): Promise<void> {
  await refuseWithATransportThatDidNotComplete();
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.UNABLE_TO_LOOKUP_ACCOUNT);

  expect(accountFormRow().className).not.toContain(ERROR_ROW_CLASS);
  expect(accountEntry()).not.toHaveAttribute('aria-invalid', 'true');
}

/**
 * A correction clears the mark the previous turn left.
 *
 * Assumptions: editing the entry discards the whole of the previous turn's outcome, the refusal
 * included, because the reference repaints its fields on every turn rather than accumulating them.
 * Leaving a mark on a field the operator has since corrected would report a refusal that no longer
 * describes the value on screen.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aCorrectionClearsTheMark(): Promise<void> {
  const { user } = await mountBillPay();

  await user.keyboard('{Enter}');
  await waitForBandToRead(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY);
  expect(accountFormRow().className).toContain(ERROR_ROW_CLASS);

  await user.type(accountEntry(), '1');

  expect(accountFormRow().className).not.toContain(ERROR_ROW_CLASS);
}

/**
 * No asterisk marker is painted, because this program does not copy the templated highlight.
 *
 * ⚠️ Refactoring Rationale: the templated copybook `app/cpy/CSSETATY.cpy` moves `DFHRED` into a
 * field's colour subfield when its validation flag is not-OK or blank, and additionally moves a
 * literal `'*'` into the field's own output subfield when it is BLANK, at L24. That copybook is not
 * reachable from this program: `app/cbl/COBIL00C.cbl` copies `COCOM01Y`, `COBIL00`, `COTTL01Y`,
 * `CSDAT01Y`, `CSMSG01Y`, `CVACT01Y`, `CVACT03Y`, `CVTRA05Y`, `DFHAID` and `DFHBMSCA`, and a
 * repository-wide search for the templated copybook finds exactly one program that copies it,
 * `app/cbl/COACTUPC.cbl`.
 *
 * Assumptions: the marker's ABSENCE is therefore the faithful outcome and is asserted as such. What
 * this program does produce per field is `MOVE -1 TO ACTIDINL` and `MOVE -1 TO CONFIRML` — the cursor
 * placement — which the case below asserts. Painting a marker here would add a signal the baseline
 * never emits, and the screens whose programs DO copy the highlight keep theirs for the same reason.
 *
 * Assumptions: the token is still read from `ui/src/theme/tokens.ts` rather than written out, so the
 * negative assertion cannot drift from the marker the bridge actually defines.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function noAsteriskMarkerIsPainted(): Promise<void> {
  const { user } = await mountBillPay();

  await user.keyboard('{Enter}');
  await waitForBandToRead(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY);

  expect(FIELD_ERROR_TOKENS.blankMarker).toBe('*');
  expect(accountFormRow().textContent).not.toContain(FIELD_ERROR_TOKENS.blankMarker);
}

/**
 * The cursor returns to the refused field, which is this program's only per-field signal.
 *
 * Assumptions: every refusal path in `app/cbl/COBIL00C.cbl` issues `MOVE -1 TO ACTIDINL` — L163 on the
 * blank entry, L203 on the nothing-to-pay advisory, L363 and L370 on a failed account read, L394 and
 * L401 on a failed rewrite — so the cursor move is the signal the program actually produces, and it is
 * the one a migration that dropped it would silently lose.
 *
 * Assumptions: the move is asserted through a wait because the screen records it as state and applies
 * it after the commit rather than inline. The controls a refusal can send the cursor to are disabled
 * while the turn is in flight, and a disabled element refuses focus silently, so an inline move would
 * land nowhere at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theCursorReturnsToTheRefusedField(): Promise<void> {
  await refuseWithATransportThatDidNotComplete();
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.UNABLE_TO_LOOKUP_ACCOUNT);

  await waitFor(
    /**
     * Waits for the recorded cursor move to be applied.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(accountEntry()).toHaveFocus();
    },
  );
}

/**
 * An attributed refusal is carried through to the screen's own shape unchanged and in order.
 *
 * Assumptions: the service composes these sentences from the baseline's own wording, so rewording one
 * on the way through would replace a transcribed refusal with an invented one — which transformation
 * rule T8 forbids and which no rendering assertion would catch, because a reworded sentence still
 * renders.
 *
 * Assumptions: ORDER is asserted as well as content, because the order the service sent them in is the
 * order a form marks its fields in and the first is the one the cursor moves to. A mapping that sorted
 * or de-duplicated the array would change which field the operator is sent to correct.
 *
 * Assumptions: the refusal names `confirmation` rather than the account field, which is what makes
 * this case the attributed counterpart of the unmarked-field case above: a document naming a field
 * this screen does not render leaves the account row unmarked while still carrying its sentence
 * through. The problem document and its refusal are both built by the harness's own builders, so
 * neither the eleven-member shape nor the two-state field vocabulary is restated here.
 *
 * Assumptions: `'accountId'` is written as a literal because it is a WIRE name rather than a member of
 * any type this file imports, so no compiler check stands behind it. It is the name
 * `services/transaction-service` publishes as `BillPaymentMapper.ACCOUNT_ID_FIELD`, recorded as such
 * at `ui/src/screens/billPay/index.tsx` L224-L228, and matching it is the whole mechanism by which a
 * refusal the service attributed lands on the row the operator has to correct.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anAttributedRefusalIsCarriedThroughUnchanged(): Promise<void> {
  const { resolveApiFieldErrors } = await import('../screens/billPay');

  const problem = apiError({
    status: 400,
    message: SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N,
    fieldErrors: [
      fieldError('confirmation', SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N),
      fieldError('accountId', BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY, 'BLANK'),
    ],
  });

  expect(resolveApiFieldErrors(problem)).toEqual([
    {
      field: 'confirmation',
      state: 'NOT_OK',
      message: SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N,
    },
    {
      field: 'accountId',
      state: 'BLANK',
      message: BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY,
    },
  ]);
}

/**
 * Registers the field-error cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function fieldErrorCases(): void {
  it('marks and explains a refused entry', aRefusedEntryIsMarkedAndExplained);
  it('marks no field when the failure names none', anUnattributedFailureMarksNoField);
  it('clears the mark when the entry is corrected', aCorrectionClearsTheMark);
  it('paints no asterisk marker, as this program copies no highlight', noAsteriskMarkerIsPainted);
  it('returns the cursor to the refused field', theCursorReturnsToTheRefusedField);
  it(
    'carries an attributed refusal through unchanged',
    anAttributedRefusalIsCarriedThroughUnchanged,
  );
}

describe('BillPayScreen field errors', fieldErrorCases);
