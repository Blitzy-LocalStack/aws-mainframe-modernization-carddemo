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
 * Nothing. Evaluating this module registers eight suites with the runner; the outcome is the
 * runner's pass or fail verdict, reported per case. The eighth is `account entry edits`, added with
 * the edit that refuses a malformed identifier instead of extracting digits from it.
 *
 * Exceptions (module analogue)
 * ----------------------------
 * A failing assertion throws, which is how a case reports. `inquiryOfCall`, `paymentOfCall` and
 * `confirmationEntry` below throw deliberately when a fixture is used against a state that cannot
 * satisfy it, so a mis-wired case fails at its own line rather than several assertions later.
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
 * ⚠️ Refactoring Rationale: the second divergence recorded here is WITHDRAWN, and this paragraph
 * records the withdrawal rather than deleting it, because the divergence was the CRITICAL defect's
 * own footprint in this file. It read that `'Invalid value. Valid values are (Y/N)...'` at
 * `app/cbl/COBIL00C.cbl` L187 could not be provoked, because the single-character `CONFIRM` field
 * had been replaced by a confirmation dialogue whose two controls could send only `'Y'` and `'N'`.
 * That was true, and it was a symptom: measured in a browser, both of those controls focused
 * themselves on appearing, so an operator who typed only the eleven account digits and pressed Enter
 * three times paid the whole balance without ever typing an affirmative character. The field the
 * mapset declares is restored — `CONFIRM LENGTH=1 ATTRB=(FSET,NORM,UNPROT)` with no `IC` at
 * `app/bms/COBIL00.bms` L115-L119 — so L187's arm is reachable again and IS provoked below, from a
 * character the operator supplied.
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

import type { BillPaymentOutcome, BillPaymentPreview, BillPaymentResponse } from '../api/types';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP } from '../hooks/useAuth';
import { BUSY_ANNOUNCEMENT_TEST_ID } from '../layout/fieldHelp';
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
  REQUEST_IN_PROGRESS,
  SCREEN_TITLES,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  messageBandWidthForMapset,
} from '../messages/messages';
import { BILL_PAY_PATH } from '../router';
import { FIELD_ERROR_TOKENS, MONEY_SIGN_TEXT_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';
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
 * ⚠️ Refactoring Rationale: TWO functions, where this factory published one. The screen no longer
 * takes `payAccountBalanceInFull` at all: it takes `inquireAccountPayableBalance` for the turn that
 * reads a balance and `payAccountBalanceConfirmed` for the turn that writes a payment, because a
 * single entry point whose only difference was whether a `confirmation` member was present made
 * "this turn cannot move money" a property of a conditional rather than of the call. Mocking the two
 * is what lets a case assert WHICH composition a turn used, which is the only way to state that a
 * reading turn cannot post.
 *
 * Trade-offs: the request BODY is therefore no longer observable from this file — the two wrappers
 * compose it, and they are the collaborators being replaced. What a case asserts instead is stronger
 * in the respect that matters: `inquireAccountPayableBalance` takes an account identifier and NOTHING
 * else, so there is no parameter through which a held confirmation could reach a reading turn, and
 * `payAccountBalanceConfirmed` refuses a non-confirming answer before it sends. The body composition
 * belongs to `ui/src/api/transactions.ts` and is asserted by that module's own suite.
 *
 * Assumptions: no third export is supplied, because nothing in the mounted tree imports one. The
 * screen's only other reference to the module is the `BillPaymentPreview` TYPE, which is erased.
 * @returns {Record<string, unknown>} The two transport functions this screen imports, as spies.
 */
function mockTransactionTransportModule(): Record<string, unknown> {
  return { inquireAccountPayableBalance: vi.fn(), payAccountBalanceConfirmed: vi.fn() };
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
 * The sentence this screen refuses a malformed or zero account entry with.
 *
 * ⚠️ Refactoring Rationale: this screen had no such edit at all, and the consequence was measured
 * twice. A paste of `{{7*7}} and ${7*7}` had its digits EXTRACTED to `'7777'`, which reached the wire,
 * returned a real balance for an account the operator never named and armed the payment; and an entry
 * of `'0'` was accepted here while the read-only account-view screen refuses it locally with zero
 * requests. A money-moving screen laxer than its read-only sibling is the wrong way round, so the
 * sibling's edit is adopted.
 *
 * Assumptions: the sentence is `COACTVWC`'s and is READ from the catalog, never retyped, because it
 * carries a DOUBLE SPACE after "must" — `app/cbl/COACTVWC.cbl` L672 — and any whitespace-collapsing
 * edit destroys it silently, the result still reading as correct English.
 *
 * Alternatives Considered: composing a sentence in this program's own voice, since the refusal is new
 * to this screen. Rejected under transformation rule T8: `app/cbl/COBIL00C.cbl` declares no such
 * literal, so a sentence written here would be text no line of the baseline holds. Borrowing the one
 * the baseline already emits for precisely this condition on precisely this field is transcription.
 */
const ACCOUNT_FILTER_REFUSAL =
  PROGRAM_MESSAGES.COACTVWC.ACCOUNT_FILTER_MUST_BE_A_NON_ZERO_11_DIGIT_NUMBER;

/** The line the borrowed refusal is transcribed from, asserted so the citation cannot drift. */
const ACCOUNT_FILTER_REFUSAL_LINES =
  PROGRAM_MESSAGE_SOURCES.COACTVWC.ACCOUNT_FILTER_MUST_BE_A_NON_ZERO_11_DIGIT_NUMBER;

/**
 * Declared width of the confirmation field, `CONFIRMI PIC X(1)`.
 *
 * Assumptions: one character, from `app/cpy-bms/COBIL00.CPY` L72 and `CONFIRM LENGTH=1` at
 * `app/bms/COBIL00.bms` L115-L119. It is a constraint on the CONTROL again: the restored field
 * accepts one character, which is what makes every value it can hold a value the reference's
 * `EVALUATE CONFIRMI` at `app/cbl/COBIL00C.cbl` L173-L191 could have received — and what makes that
 * evaluate's four arms total, since one position admits no multi-character value to disambiguate.
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
 *
 * Refactoring Rationale: the fixture is still written as a whole `BillPaymentOutcome` and this
 * installer routes it, where it used to arm one spy unconditionally. Keeping the outcome shape is
 * what lets every existing case name what the SERVICE reports rather than which client function the
 * screen happens to call to get it; the tag on the outcome already says which turn it answers, so
 * the routing is a fact about the fixture rather than a decision this helper makes.
 *
 * Assumptions: a case needing both turns calls this twice, once per outcome, and the order does not
 * matter because the two spies are independent.
 * @param {BillPaymentOutcome} outcome - The outcome the service is to report.
 * @returns {Promise<void>} Resolves once the mocked module has been resolved and armed.
 */
async function answerWith(outcome: BillPaymentOutcome): Promise<void> {
  const { inquireAccountPayableBalance, payAccountBalanceConfirmed } =
    await import('../api/transactions');
  if (outcome.outcome === 'PREVIEWED') {
    vi.mocked(inquireAccountPayableBalance).mockResolvedValue(outcome.preview);
    return;
  }
  vi.mocked(payAccountBalanceConfirmed).mockResolvedValue(outcome.payment);
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
  const { inquireAccountPayableBalance, payAccountBalanceConfirmed } =
    await import('../api/transactions');
  /*
   * WHY : Assumptions: BOTH compositions are armed to reject, because a case using this fixture is
   *       stating that the transport did not complete -- which is a property of the transport and not
   *       of which turn happened to be taken. Arming one would make a case pass or fail on the
   *       screen's internal choice of entry point rather than on the outcome it asserts.
   */
  vi.mocked(inquireAccountPayableBalance).mockRejectedValue(
    new Error('the request did not complete'),
  );
  vi.mocked(payAccountBalanceConfirmed).mockRejectedValue(
    new Error('the request did not complete'),
  );
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
 * Locates the single-position confirmation field the mapset declares.
 *
 * ⚠️ Refactoring Rationale: this returns an INPUT, where it returned the button that opened a
 * confirmation dialogue. The dialogue is withdrawn: `CONFIRM` at `app/bms/COBIL00.bms` L115-L119 is
 * `ATTRB=(FSET,NORM,UNPROT) LENGTH=1` and `app/cbl/COBIL00C.cbl` L173-L191 pays only on a character
 * the operator typed into it, so the answer is a keystroke rather than a control an Enter can fall
 * onto. Asserting the element TYPE here is what makes that structural: a case cannot pass against a
 * button again without this helper failing first.
 *
 * Assumptions: the handle is the one the screen publishes, and the screen deliberately kept the
 * value the withdrawn trigger carried — `ui/src/screens/billPay/index.tsx` exports it as
 * `CONFIRMATION_CONTROL_TEST_ID` — so a cross-screen suite that locates "the control that answers
 * the confirmation" still finds the control that answers it.
 *
 * Trade-offs: queried by test identifier rather than by its label, even though the field HAS a label
 * now. The label is the 53-character prompt `app/bms/COBIL00.bms` L109-L113 paints, and the prompt is
 * also the sentence the row-23 line carries on the same turn, so a label query would be satisfied by
 * two different elements' worth of the same text and would stop being a statement about the control.
 * @returns {HTMLInputElement} The confirmation field.
 * @throws {Error} If the field is absent or is not an input.
 */
function confirmationEntry(): HTMLInputElement {
  const control = screen.getByTestId('billpay-confirm');
  if (!(control instanceof HTMLInputElement)) {
    throw new Error('the confirmation control is not an input element');
  }
  return control;
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
 * Counts every request the screen has made in the current case, across both compositions.
 *
 * Purpose: let a case state "no request was made" and "exactly one request was made" without
 * knowing which entry point the screen chose, which is what the zero-request assertions are about.
 * @returns {Promise<number>} The total number of turns that reached the transport.
 */
async function turnsTaken(): Promise<number> {
  const { inquireAccountPayableBalance, payAccountBalanceConfirmed } =
    await import('../api/transactions');
  return (
    vi.mocked(inquireAccountPayableBalance).mock.calls.length +
    vi.mocked(payAccountBalanceConfirmed).mock.calls.length
  );
}

/**
 * Counts only the turns that could have written a payment.
 *
 * Purpose: this is the count a money-safety case cares about. A screen that read a balance twice has
 * done nothing an operator can object to; a screen that reached the paying composition once without
 * being told to has moved money.
 * @returns {Promise<number>} The number of payment requests the screen composed.
 */
async function paymentsComposed(): Promise<number> {
  const { payAccountBalanceConfirmed } = await import('../api/transactions');
  return vi.mocked(payAccountBalanceConfirmed).mock.calls.length;
}

/**
 * Reads the account identifier one reading turn sent.
 * @param {number} turn - Zero-based index of the reading turn, in call order.
 * @returns {Promise<string>} The identifier that turn looked up.
 * @throws {Error} If no such turn was taken, which names the index rather than failing later on a
 *   property of `undefined`.
 */
async function inquiryOfCall(turn: number): Promise<string> {
  const { inquireAccountPayableBalance } = await import('../api/transactions');
  const sent = vi.mocked(inquireAccountPayableBalance).mock.calls[turn];
  if (sent === undefined) {
    throw new Error(`no reading turn was taken at index ${String(turn)}`);
  }
  return sent[0];
}

/**
 * Reads every argument one reading turn sent, so a case can assert what it did NOT send.
 *
 * ⚠️ Assumptions: the argument COUNT is the assertion that matters here, not just the identifier.
 * `inquireAccountPayableBalance` at `ui/src/api/transactions.ts` L1023 takes one parameter and calls
 * the shared transport with `{ accountId }` alone, so a reading turn structurally cannot carry a
 * confirmation. Asserting the arity is therefore how a case proves the screen reached the READ-ONLY
 * composition and not the paying one with a defaulted answer — the paying wrapper at L1059 defaults
 * its second parameter to the confirming character, so an argument list of length one is a different
 * fact there and would move money.
 * @param {number} turn - Zero-based index of the reading turn, in call order.
 * @returns {Promise<readonly unknown[]>} Every argument that turn passed, in order.
 * @throws {Error} If no such turn was taken, which names the index rather than failing later on a
 *   property of `undefined`.
 */
async function inquiryArgumentsOfCall(turn: number): Promise<readonly unknown[]> {
  const { inquireAccountPayableBalance } = await import('../api/transactions');
  const sent = vi.mocked(inquireAccountPayableBalance).mock.calls[turn];
  if (sent === undefined) {
    throw new Error(`no reading turn was taken at index ${String(turn)}`);
  }
  return sent;
}

/**
 * Reads the arguments one paying turn sent.
 * @param {number} turn - Zero-based index of the paying turn, in call order.
 * @returns {Promise<readonly unknown[]>} Every argument that turn passed, in order.
 * @throws {Error} If no such turn was taken, which names the index rather than failing later on a
 *   property of `undefined`.
 */
async function paymentOfCall(turn: number): Promise<readonly unknown[]> {
  const { payAccountBalanceConfirmed } = await import('../api/transactions');
  const sent = vi.mocked(payAccountBalanceConfirmed).mock.calls[turn];
  if (sent === undefined) {
    throw new Error(`no paying turn was taken at index ${String(turn)}`);
  }
  return sent;
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
 * Types one character into the confirmation field and takes the turn with the Enter key.
 *
 * ⚠️ Refactoring Rationale: the answer is TYPED and then Enter is pressed, where this helper used
 * to click a dialogue trigger and then click the dialogue's own answer. That is not a mechanical
 * translation of the same gesture: it is the difference the CRITICAL finding turns on. Both of the
 * clicked controls focused themselves on appearing, so Enter alone reached both of them in sequence,
 * and an operator who typed only the account digits and pressed Enter three times paid the full
 * balance without supplying an affirmative character. A typed answer cannot be supplied by the Enter
 * key, which is the property `app/cbl/COBIL00C.cbl` L173-L191 relies on.
 *
 * Assumptions: the field is FOCUSED before the character is typed, and the case does not need to
 * arrange that — a settled turn that offers payment places the cursor there itself, which is
 * `MOVE -1 TO CONFIRML` at L239. It is focused explicitly here anyway so that this helper works
 * whether or not the case under it has moved the cursor in between.
 * @param {HarnessRenderResult['user']} user - The keyboard operator from the render result.
 * @param {string} answer - The single character to answer with, such as `'Y'` or `'N'`.
 * @returns {Promise<void>} Resolves once the answer has been typed and the turn dispatched.
 * @throws {Error} If the confirmation field is absent, which {@link confirmationEntry} raises.
 */
async function answerConfirmation(
  user: HarnessRenderResult['user'],
  answer: string,
): Promise<void> {
  await user.click(confirmationEntry());
  await user.keyboard(answer);
  await user.keyboard('{Enter}');
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
 * Asserts that the document's active element is the single-position confirmation field.
 *
 * ⚠️ Refactoring Rationale: this exists so the CRITICAL finding's reproduction can assert the cursor
 * after EVERY press rather than once at the end. What was measured in a browser as the defect was not
 * "money moved" — that was the consequence — it was that a control whose activation COMMITS held the
 * cursor when a bare Enter arrived. Asserting the resting place per press is therefore asserting the
 * mechanism, and a future edit that re-focuses a commit control at any one of the three turns fails
 * here even if the paying composition happens to be unreachable for some other reason that day.
 *
 * Assumptions: `document.activeElement` is compared by identity against the element `confirmationEntry`
 * returns, and that helper already refuses anything that is not an `HTMLInputElement`. So this asserts
 * both "the cursor is on the confirmation field" and "the confirmation field is still an input", which
 * together are the whole of the withdrawn dialogue's absence.
 *
 * Assumptions: the comparison is wrapped in a wait because the screen applies the cursor move in an
 * effect rather than inside the turn — `ui/src/screens/billPay/index.tsx` defers it through
 * `pendingFocus` because focusing a control that is still disabled by the busy window does nothing.
 * @returns {Promise<void>} Resolves once the cursor has been observed on the confirmation field.
 */
async function expectCursorOnTheConfirmationField(): Promise<void> {
  await waitFor(
    /**
     * Compares the active element with the confirmation field.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(document.activeElement).toBe(confirmationEntry());
    },
  );
}

/**
 * The account entry refuses a twelfth character and refuses a non-digit.
 *
 * Assumptions: eleven is asserted individually rather than through a table of the screen's three
 * fields, and the reason is that the three are asserted by three different means: the balance is
 * `ATTRB=(ASKIP,FSET,NORM)` at `app/bms/COBIL00.bms` L103-L106 and so has no attribute to read, the
 * confirmation is one position wide and is asserted with its own domain below, and only this field
 * has both a width and a content edit. Trade-offs: a table would have to carry a per-row "how do I
 * assert this" column for three rows, which is more machinery than three named assertions and hides
 * the citation each row exists to carry.
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

  // WHY : ⚠️ Refactoring Rationale: a non-digit is kept in the control and REFUSED on the turn, where
  //       it used to be filtered out on the way in. The filter's failure was measured rather than
  //       theorised: `'ab12cd34'` became `'1234'`, and a paste of `{{7*7}} and ${7*7}` became `'7777'`
  //       — an entry the operator never typed, which was then looked up and returned a real balance
  //       for a real account with the payment armed against it. A 3270 numeric field DISCARDED a
  //       non-numeric keystroke; it never compacted the digits out of a longer string into a different
  //       well-formed identifier. So the characters survive and the edit refuses them.
  await user.type(accountEntry(), 'ab12cd34');
  expect(accountEntry().value).toBe('ab12cd34');

  await user.keyboard('{Enter}');
  await waitForBandToRead(ACCOUNT_FILTER_REFUSAL);
  expect(await turnsTaken()).toBe(0);
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

  // WHY : Assumptions: the confirmation field's own focus move is a DIFFERENT thing and must not be
  //       mistaken for a second `IC`. It is the `MOVE -1 TO CONFIRML` of `app/cbl/COBIL00C.cbl` L239,
  //       issued alongside the confirmation prompt on a LATER turn; `CONFIRM` at
  //       `app/bms/COBIL00.bms` L115-L119 carries no `IC` of its own, so the field is present from the
  //       first paint and holds the cursor on none of it.
  expect(confirmationEntry()).not.toHaveFocus();
  expect(confirmationEntry()).toBeDisabled();
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
  // WHY : Refactoring Rationale: the count is TWO, where it was one. The mapset declares two
  //       unprotected fields — `ACTIDIN` at L85-L89 and `CONFIRM` at L115-L119 — and the second was
  //       missing from the rendered screen while the confirmation was a dialogue. Asserting the pair
  //       by identity rather than only by count is what states which two they are.
  const controls = screen.getAllByRole('textbox');
  expect(controls).toHaveLength(2);
  expect(controls).toContain(accountEntry());
  expect(controls).toContain(confirmationEntry());
  expect(controls).not.toContain(paintedBalance());
}

/**
 * The confirmation offers exactly the two single-character answers the mapset's hint names.
 *
 * Assumptions: `CONFIRM` is `LENGTH=1` at `app/bms/COBIL00.bms` L115-L119 and `CONFIRMI PIC X(1)` at
 * `app/cpy-bms/COBIL00.CPY` L72, and `app/cbl/COBIL00C.cbl` L173-L191 accepts `'Y'`/`'y'`, `'N'`/`'n'`
 * or blank there and refuses everything else. All four of those arms are asserted against the
 * screen's own exported classifier, which is the function the Enter handler dispatches on, so the
 * case states the DOMAIN rather than one path through it.
 *
 * ⚠️ Refactoring Rationale: the four arms are asserted because a dialogue could express only two of
 * them. Its two controls sent `'Y'` and `'N'`, which left the never-answered arm at L182-L184 with no
 * representation at all — and a turn with no representation for "the operator has not answered yet"
 * is a turn on which Enter has to mean something else, which is how Enter came to mean pay.
 *
 * Assumptions: the width is asserted on the CONTROL and the domain on the classifier, because they
 * are two different contracts. One position is what the mapset declares; which characters that
 * position admits is what the program evaluates.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theConfirmationDomainIsTheTwoAnswersTheMapsetNames(): Promise<void> {
  const { classifyConfirmationAnswer } = await import('../screens/billPay');
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  // WHY : Assumptions: the five-character domain hint names the two answers the field admits. It is
  //       `LENGTH=5` at `app/bms/COBIL00.bms` L122-L126, and its parentheses are part of the literal.
  expect(screen.getByText(BILL_PAY_CONFIRM_DOMAIN_HINT)).toBeInTheDocument();
  expectMaxLength(confirmationEntry(), CONFIRMATION_DECLARED_WIDTH);

  expect(classifyConfirmationAnswer('Y')).toBe('PAY');
  expect(classifyConfirmationAnswer('y')).toBe('PAY');
  expect(classifyConfirmationAnswer('N')).toBe('DECLINE');
  expect(classifyConfirmationAnswer('n')).toBe('DECLINE');
  expect(classifyConfirmationAnswer('')).toBe('PREVIEW');
  expect(classifyConfirmationAnswer('X')).toBe('REFUSE');
  expect(classifyConfirmationAnswer(' ')).toBe('REFUSE');

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitFor(
    /**
     * Waits for the payment to be offered.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationEntry()).toBeEnabled();
    },
  );

  // WHY : Assumptions: NO dialogue is opened by the field receiving the cursor, which is the property
  //       that makes an Enter arriving here harmless. A panel would carry its own default action, and
  //       an empty one-position input carries none.
  expect(screen.queryByRole('tooltip')).toBeNull();
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(confirmationEntry().value).toBe('');
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
  // WHY : Refactoring Rationale: the confirmation carries the design system's INPUT class, where it
  //       carried its button class. The mapset declares an unprotected one-position field, so the
  //       library primitive that maps onto it is `Input`; `Button` is what the withdrawn dialogue's
  //       trigger was, and that trigger is the control an Enter could fall onto.
  expect(confirmationEntry().className).toContain('ant-input');
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
    'bounds the account entry at eleven positions and refuses the rest',
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
    'evaluates the confirmation field over all four of its arms',
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
 * A pasted template-injection string is refused whole, with no digits extracted from it.
 *
 * ⚠️ Refactoring Rationale: this is the reproduction the HIGH finding was raised on, and the
 * measurement is what makes it a finding rather than a preference. A paste of `{{7*7}} and ${7*7}` had
 * its digits EXTRACTED into `'7777'`; the screen then looked that up, received a real payable balance
 * for an account the operator had never named, and armed the payment against it. Nothing on screen
 * said the value had been altered. Silent digit extraction from a malformed identifier is never
 * acceptable on a screen that moves money — and it is not what the terminal did either: a 3270 numeric
 * field DISCARDED a non-numeric keystroke, it never compacted a longer string into a different
 * well-formed identifier.
 *
 * Assumptions: the value is PASTED rather than typed, because `user.type` parses `{` as the opening of
 * a key descriptor and the finding's own input begins with two of them. Pasting is also the gesture
 * the finding used, so the case reproduces it rather than an approximation of it.
 *
 * Assumptions: the declared width still truncates the paste — eleven positions, from `ACTIDIN
 * LENGTH=11` at `app/bms/COBIL00.bms` L85-L89 — so what lands in the field is the first eleven
 * characters as supplied. That is the terminal's own behaviour and it is asserted alongside the
 * refusal, because the property under test is that the characters are the operator's and the refusal
 * names them, not that the control accepts an unbounded value.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aPastedInjectionStringIsRefusedWhole(): Promise<void> {
  const { user } = await mountBillPay();

  await user.click(accountEntry());
  await user.paste('{{7*7}} and ${7*7}');

  /*
   * WHY : Refactoring Rationale: the entry is compared against the WHOLE measured value rather than
   *       tested for the absence of `'7777'` and the presence of a fragment. `'{{7*7}} and'` is the
   *       first eleven characters of the paste, and it is what was read out of the control in a
   *       browser after the fix -- so this is the finding's own measurement, not a paraphrase of it.
   *       Alternatives Considered: keeping the three looser assertions. They pass against a screen
   *       that extracted `'7'` alone, or one that dropped the space, or one that kept twelve
   *       characters -- every one of those is a silent substitution of a value the operator did not
   *       type, which is the whole of what the finding is about.
   */
  expect(accountEntry().value).toBe('{{7*7}} and');
  expect(accountEntry().value).toHaveLength(11);

  await user.keyboard('{Enter}');

  /*
   * WHY : Alternatives Considered: `expectVerbatimMessage`, which is the helper this file reaches for
   *       elsewhere to defeat whitespace collapsing. It cannot be used here: this turn paints the
   *       sentence TWICE by design -- once on the row-23 line and once as the marked field's help text
   *       -- so an unscoped query matches both and raises before it can compare anything.
   *       `waitForBandToRead` is the stronger assertion in any case, because it compares the band's
   *       RAW `textContent` rather than a normalised form, so the doubled space is asserted by it.
   */
  await waitForBandToRead(ACCOUNT_FILTER_REFUSAL);
  /*
   * WHY : Trade-offs: the field's copy is read off the design system's own explain container and
   *       compared as RAW text, rather than located with `getByText`. Testing Library's default
   *       normaliser collapses a run of spaces before comparing, so a query for this sentence would
   *       not match the element that renders it -- the very collapsing the doubled space is at risk
   *       of. Reading the container's `textContent` is the comparison that can see the two spaces.
   */
  const explain = accountFormRow().querySelector('.ant-form-item-explain');
  expect(explain?.textContent).toBe(ACCOUNT_FILTER_REFUSAL);

  // WHY : Assumptions: BOTH counts are asserted, not just the total. The finding's harm was that the
  //       extracted identifier reached the service and came back with a real balance, which armed the
  //       payment against a record nobody named — so the paying composition's own count is named
  //       here as well as the total, because that is the count the CRITICAL finding composed with.
  expect(await turnsTaken()).toBe(0);
  expect(await paymentsComposed()).toBe(0);

  // WHY : Assumptions: the refusal carries a DOUBLE SPACE after "must" and the assertion names it,
  //       because the sentence still reads as correct English without it — so a whitespace-collapsing
  //       edit anywhere between the catalog and the band would be invisible to a reader and to every
  //       matcher that normalises. The line it is transcribed from is asserted with it.
  expect(ACCOUNT_FILTER_REFUSAL).toContain('must  be');
  expect(ACCOUNT_FILTER_REFUSAL_LINES).toContain(672);

  expect(paintedBalance()).toBeEmptyDOMElement();
  expect(confirmationEntry()).toBeDisabled();
  expect(accountFormRow().className).toContain(ERROR_ROW_CLASS);
}

/**
 * An all-zeroes entry is refused locally, as the read-only sibling screen already refuses it.
 *
 * ⚠️ Refactoring Rationale: this screen accepted `'0'` and looked it up, while
 * `app/cbl/COACTVWC.cbl` L666-L680 refuses the identical value on the identical field with no file
 * access at all — so the money-moving screen was laxer than the read-only one, which is the wrong way
 * round. The sibling's edit is adopted here and its sentence borrowed with it.
 *
 * Assumptions: the zero test compares against a run of zeroes at the DECLARED WIDTH rather than
 * converting the entry to a number. `app/cpy/CVCRD01Y.cpy` L34-L36 declares `CC-ACCT-ID PIC X(11)`
 * with a numeric `REDEFINES`, so the identifier is characters on the wire and a number only inside
 * arithmetic; converting it would discard the leading zeroes the declared width owns and would put an
 * eleven-digit value through an IEEE-754 double on the way.
 *
 * Assumptions: both the single `'0'` and the eleven-position `'00000000000'` are driven, because they
 * are refused by two different halves of one edit — the first fails the width, the second passes the
 * width and fails the non-zero test — and a screen could implement one without the other.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anAllZeroesEntryIsRefusedLocally(): Promise<void> {
  const { user } = await mountBillPay();

  await user.type(accountEntry(), '0');
  await user.keyboard('{Enter}');
  await waitForBandToRead(ACCOUNT_FILTER_REFUSAL);
  expect(await turnsTaken()).toBe(0);

  await user.clear(accountEntry());
  await user.type(accountEntry(), '00000000000');
  expect(accountEntry().value).toHaveLength(11);

  await user.keyboard('{Enter}');
  await waitForBandToRead(ACCOUNT_FILTER_REFUSAL);
  expect(await turnsTaken()).toBe(0);
  expect(paintedBalance()).toBeEmptyDOMElement();
  expect(confirmationEntry()).toBeDisabled();
}

/**
 * An entry narrower than the declared width is refused rather than looked up.
 *
 * Assumptions: a `PIC 9(11)` field cannot hold a shorter value — a three-digit entry does not fill it
 * — so a wrong width is refused by the same edit that refuses a non-digit, which is how
 * `app/cbl/COACTVWC.cbl` L666-L680 expresses the rule with one `NOT NUMERIC` test.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aShortEntryIsRefusedLocally(): Promise<void> {
  const { user } = await mountBillPay();

  await user.type(accountEntry(), '11');
  await user.keyboard('{Enter}');

  await waitForBandToRead(ACCOUNT_FILTER_REFUSAL);
  expect(await turnsTaken()).toBe(0);
}

/**
 * Registers the account-entry edit cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function accountEntryEditCases(): void {
  it('refuses a pasted injection string whole', aPastedInjectionStringIsRefusedWhole);
  it('refuses an all-zeroes entry without reaching the service', anAllZeroesEntryIsRefusedLocally);
  it('refuses an entry narrower than its declared width', aShortEntryIsRefusedLocally);
}

describe('BillPayScreen account entry edits', accountEntryEditCases);

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
  const { user } = await mountBillPay();

  await user.keyboard('{Enter}');

  // WHY : Assumptions: the sentence is asserted on the BAND specifically rather than found anywhere
  //       in the document, because this one turn paints it TWICE — once on the row-23 line and once
  //       as the marked field's help text, deliberately, so that the field marker and the message
  //       line carry one wording between them. An unscoped query for it matches both, and the field
  //       copy is asserted in the field-error suite where it belongs.
  await waitForBandToRead(BILL_PAY_MESSAGES.ACCT_ID_CAN_NOT_BE_EMPTY);
  expect(await turnsTaken()).toBe(0);
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
  await answerConfirmation(user, 'Y');

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
  await answerConfirmation(user, 'Y');

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
  /*
   * WHY : Refactoring Rationale: the entry is RE-TYPED before the second turn, where the second turn
   *       used to be a bare press of the legend control. With a payment standing from the first turn,
   *       a bare Enter is answered locally and makes no request at all — which is the CRITICAL fix
   *       working, not an obstacle to it. Editing the entry withdraws the standing offer, so the
   *       screen is back on its reading turn and the legend control dispatches one.
   */
  await user.clear(accountEntry());
  await user.type(accountEntry(), FIXTURE_ACCOUNT_ID);
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
 * The balance keeps the colour declaration its own sign resolved to.
 *
 * Purpose: hold the narrowing that resolves the sign's token name against the theme's token map. The
 * renderer answers a token NAME, the screen resolves it through that map, and the resolved reference
 * is the value the style carries. A narrowing that lost the reference would leave the field painted
 * in inherited text and nothing else about the screen would change, so the declaration is asserted
 * rather than inferred.
 *
 * Assumptions: the fixture balance is positive, so the sign resolves to
 * `MONEY_SIGN_TEXT_TOKENS.positive`. That is the baseline's own resolution for an unmarked money
 * field, which is why the case names the token through the money module's map rather than writing a
 * token name out: a change to which token a positive balance takes is then a change this case follows
 * instead of one it contradicts.
 *
 * Alternatives Considered: asserting a resolved colour. Rejected because jsdom resolves no custom
 * property and AAP section 0.3.2 admits no literal design value in this tree, which is the same
 * reasoning recorded on the fixed-pitch case above.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theBalanceKeepsItsSignsColourDeclaration(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  const painted = paintedBalance();

  expect(painted.style.color).toContain(customPropertySegmentOf(MONEY_SIGN_TEXT_TOKENS.positive));
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
  expect(confirmationEntry()).toBeDisabled();

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
  expect(confirmationEntry()).toBeDisabled();

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
      expect(confirmationEntry()).toBeEnabled();
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
  expect(confirmationEntry()).toBeDisabled();
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
      expect(confirmationEntry()).toBeEnabled();
    },
  );

  // WHY : Assumptions: the edit is a CLEAR rather than an appended digit, and the reason is the width
  //       contract asserted above: the entry already holds its eleven declared positions, so a twelfth
  //       keystroke is dropped by `maxLength`, no change event is raised and a case that appended one
  //       would assert nothing at all. Clearing is an edit that the field can actually receive.
  await user.clear(accountEntry());

  expect(paintedBalance()).toBeEmptyDOMElement();
  expect(confirmationEntry()).toBeDisabled();
}

/**
 * Registers the balance and zero-guard cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function balanceCases(): void {
  it('carries the balance as text with no numeric conversion', theBalanceIsCarriedAsTextThroughout);
  it('paints the balance in the fixed-pitch token', theBalanceIsPaintedInTheFixedPitchToken);
  it(
    'keeps the colour declaration the balance sign resolved to',
    theBalanceKeepsItsSignsColourDeclaration,
  );
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
  //       keystroke the reference discards. The count covers BOTH compositions, so a key that reached
  //       the paying one is caught by the same assertion as one that only read a balance.
  expect(await turnsTaken()).toBe(0);
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
 *
 * ⚠️ Refactoring Rationale: the fixture is the nothing-to-pay one, where it was a payable balance,
 * and the substitution is the CRITICAL fix showing through. With a payment standing, a second bare
 * Enter is answered locally and reaches nothing — deliberately, because that is the state in which an
 * Enter used to pay. A turn that reports no payable balance leaves the screen on its reading turn, so
 * both channels dispatch a reading turn and the case can still state that both arrive.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function enterTakesTheTurnFromBothChannels(): Promise<void> {
  await answerWith(nothingToPayOn('0.00'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.YOU_HAVE_NOTHING_TO_PAY);
  expect(await turnsTaken()).toBe(1);

  await user.click(legendControl(BILL_PAY_KEY_LABELS.ENTER));
  await waitFor(
    /**
     * Waits for the second turn to be dispatched.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    async (): Promise<void> => {
      expect(await turnsTaken()).toBe(2);
    },
  );

  // WHY : Assumptions: the two turns looked up the SAME identifier, which is what shows the legend
  //       control and the key press ran one handler rather than two that merely look alike.
  expect(await inquiryOfCall(1)).toBe(await inquiryOfCall(0));
  // WHY : Assumptions: neither channel reached the PAYING composition, which is the half of this case
  //       that matters for money. A control wired to the wrong dispatcher would still take a turn.
  expect(await paymentsComposed()).toBe(0);
}

/**
 * The legend's emphasis follows what each key will DO, and Enter's changes with the answer.
 *
 * ⚠️ Refactoring Rationale: this asserted that Enter is always primary and the other two always
 * default, which was the `PRIMARY_ACTION_AIDS` fallback in `ui/src/layout/PfKeyBar.tsx` — a table keyed
 * on the attention identifier. That table cannot express this screen: `ENTER=Continue` is one caption
 * over two actions, and `app/cbl/COBIL00C.cbl` L182-L184 writes nothing while L173-L176 into L210-L235
 * writes the ledger row and reduces the balance. Emphasis keyed on the AID therefore said "strongest
 * control on the bar" on the reading turn, where nothing is at stake, and said exactly the same thing
 * on the paying turn — so the signal carried no information at either.
 *
 * Assumptions: the screen now declares a risk per entry and the bar resolves emphasis from it —
 * `'mutating'` to solid primary, `'read-only'` to default — so the assertion is on that mapping and it
 * is driven through the two states the screen actually has. On the reading turn Enter is DEFAULT, which
 * is the change this case records; once the confirming character is in the field it is PRIMARY.
 *
 * Assumptions: `'destructive'` is asserted ABSENT on the paying turn, and that is a contract rather
 * than an omission. `pfKeyEmphasisFor` resolves destructive to `danger:true`, and
 * `ui/src/layout/PfKeyBar.tsx` wraps a dangerous control in `destructiveFocusTheme` — a treatment this
 * screen declines, because that theme overrides only `components.Button.colorPrimaryBorder` and there
 * is no destructive `Button` left on the screen for it to act on since the confirmation dialogue was
 * withdrawn.
 *
 * Assumptions: the variant is read off the rendered class rather than from a prop, because a prop is
 * not observable from a test and the class is what a browser actually styles from.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theEmphasisFollowsWhatEachKeyWillDo(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER).className).toContain('ant-btn-default');
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER).className).not.toContain('ant-btn-primary');
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03).className).toContain('ant-btn-default');
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04).className).toContain('ant-btn-default');

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  // WHY : Assumptions: an OFFERED payment is still not an emphatic one. The offer stands and the field
  //       is empty, so the next Enter takes the never-answered arm and reads — which is read-only, and
  //       is why the emphasis is asserted here as well as before the lookup.
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER).className).toContain('ant-btn-default');

  await user.clear(confirmationEntry());
  await user.type(confirmationEntry(), 'Y');

  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER).className).toContain('ant-btn-primary');
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER).className).not.toContain('ant-btn-dangerous');
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03).className).toContain('ant-btn-default');
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04).className).toContain('ant-btn-default');

  // WHY : Assumptions: correcting the answer withdraws the emphasis again, so the paint tracks the
  //       state rather than latching on the first confirming keystroke of the session.
  await user.clear(confirmationEntry());
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER).className).toContain('ant-btn-default');
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
      expect(confirmationEntry()).toBeEnabled();
    },
  );

  await pressPfKey(user, 'PFK04');

  expect(accountEntry().value).toBe('');
  expect(paintedBalance()).toBeEmptyDOMElement();
  expect(messageBand().textContent).toBe('');
  expect(confirmationEntry()).toBeDisabled();

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
  expect(await turnsTaken()).toBe(0);
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
  it('emphasises Enter only on the turn that pays', theEmphasisFollowsWhatEachKeyWillDo);
  it('reinitialises every field on the clear key', theClearKeyReinitialisesEveryField);
  it('returns to the main menu on the back key', theBackKeyReturnsToTheMainMenu);
  it('returns to the main menu on the back control', theBackControlReturnsToTheMainMenu);
}

describe('BillPayScreen function keys', pfKeyCases);

/**
 * Arms the reading turn with a promise this file settles, so the in-flight state can be observed.
 *
 * Purpose: hold one reading turn open for as long as a case needs, so the affordances the screen
 * shows while a turn is locked can be asserted rather than inferred.
 *
 * Assumptions: a promise held open is the only way to observe the locked state at all. An armed
 * resolution settles inside the same act-flush as the keystroke that dispatched it, so by the time a
 * case's next line runs the turn is already over and every busy affordance has been released.
 * @returns {Promise<(preview: BillPaymentPreview) => void>} A function that settles the held turn
 *   with the reported balance.
 */
async function deferTheReadingTurn(): Promise<(preview: BillPaymentPreview) => void> {
  const { inquireAccountPayableBalance } = await import('../api/transactions');
  let release: (preview: BillPaymentPreview) => void =
    /**
     * Stands in for the resolver until the promise's own executor supplies the real one.
     *
     * Assumptions: the placeholder is never the value returned. A promise executor runs synchronously
     * inside the constructor, so the capture below has replaced this by the time the helper returns.
     * @returns {void} Nothing; the placeholder discards the settling value.
     */
    (): void => undefined;
  vi.mocked(inquireAccountPayableBalance).mockReturnValue(
    new Promise<BillPaymentPreview>(
      /**
       * Captures the resolver so the case can settle the turn when it chooses.
       * @param {(preview: BillPaymentPreview) => void} resolve - The promise's own resolver.
       * @returns {void} Nothing; the captured resolver is the helper's result.
       */
      (resolve: (preview: BillPaymentPreview) => void): void => {
        release = resolve;
      },
    ),
  );
  return release;
}

/**
 * Arms the paying turn with a promise this file settles, for the same reason as the reading one.
 * @returns {Promise<(payment: BillPaymentResponse) => void>} A function that settles the held
 *   payment with the row the service wrote.
 */
async function deferThePayingTurn(): Promise<(payment: BillPaymentResponse) => void> {
  const { payAccountBalanceConfirmed } = await import('../api/transactions');
  let release: (payment: BillPaymentResponse) => void =
    /**
     * Stands in for the resolver until the promise's own executor supplies the real one.
     *
     * Assumptions: the placeholder is never the value returned, for the reason recorded on the reading
     * turn's equivalent -- a promise executor runs synchronously inside the constructor.
     * @returns {void} Nothing; the placeholder discards the settling value.
     */
    (): void => undefined;
  vi.mocked(payAccountBalanceConfirmed).mockReturnValue(
    new Promise<BillPaymentResponse>(
      /**
       * Captures the resolver so the case can settle the payment when it chooses.
       * @param {(payment: BillPaymentResponse) => void} resolve - The promise's own resolver.
       * @returns {void} Nothing; the captured resolver is the helper's result.
       */
      (resolve: (payment: BillPaymentResponse) => void): void => {
        release = resolve;
      },
    ),
  );
  return release;
}

/**
 * Locates the form row that surrounds the confirmation field.
 *
 * Assumptions: the row is reached from the control rather than by a class query, for the same reason
 * the account entry's is — the control is the element a case has a stable handle on, and the design
 * system's own row is what carries the refused state.
 * @returns {HTMLElement} The form row.
 * @throws {Error} If the control is not inside a form row, which would mean the confirmation is no
 *   longer composed through the design system's form primitive at all.
 */
function confirmationFormRow(): HTMLElement {
  const row = confirmationEntry().closest('.ant-form-item');
  if (!(row instanceof HTMLElement)) {
    throw new Error('the confirmation field is not inside a design-system form row');
  }
  return row;
}

/**
 * Payment is offered only once a balance has been reported, and only from a typed answer.
 *
 * ⚠️ Refactoring Rationale: the gate asserted here is the mapset's own FIELD, where it was a
 * confirmation dialogue. AAP section 0.4.1.4 maps a destructive confirmation onto a dialogue in
 * general, and this screen is the documented exception rather than a lapse from it: the reference
 * already carries a confirmation gate of its own, and `app/cbl/COBIL00C.cbl` L173-L191 pays only on a
 * character the operator typed into `CONFIRM`. A dialogue on top of a typed answer would ask one
 * question twice, which trains an operator to answer without reading; a dialogue INSTEAD of it was
 * measured moving a full balance on three bare presses of Enter.
 *
 * Assumptions: the 53-character prompt is the field's LABEL, trailing space and full stop included.
 * `'Do you want to pay your balance now. Please confirm: '` is `LENGTH=53` at
 * `app/bms/COBIL00.bms` L109-L114 as a BMS continuation across two lines, and it ends its first
 * sentence with a full stop rather than a question mark. Both read like transcription slips and
 * neither is corrected, so the literal is taken from the catalog and never retyped.
 *
 * Assumptions: the record the question is about is painted BESIDE the question and is never occluded
 * by it, which a dialogue could not guarantee — measured, the panel covered the balance it asked
 * about. The label and the control sit in the same form row as the balance's own line, so the figure
 * and the amount stay readable while the answer is typed.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theConfirmationGatesThePayment(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  // WHY : Assumptions: the field starts UNAVAILABLE, because the reference reaches its confirmation
  //       prompt at L236-L240 only after a balance has been read — L208's `IF NOT ERR-FLG-ON` gate
  //       means no turn that failed or reported nothing to pay ever offers it.
  expect(confirmationEntry()).toBeDisabled();
  expect(screen.queryByRole('tooltip')).toBeNull();
  expect(screen.queryByRole('dialog')).toBeNull();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitFor(
    /**
     * Waits for the payment to be offered.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationEntry()).toBeEnabled();
    },
  );

  // WHY : Assumptions: the record the confirmation is about is NAMED on the screen the answer is
  //       typed on — the identifier in its own entry and the amount in the balance field — and both
  //       are readable at the moment of answering. That is the umbrella confirmation contract this
  //       screen satisfies with an in-place gate rather than with a panel.
  expect(accountEntry().value).toBe(FIXTURE_ACCOUNT_ID);
  expect(paintedBalance().textContent).toBe('+0000001234.56');
  expect(screen.queryByRole('tooltip')).toBeNull();
  expect(screen.queryByRole('dialog')).toBeNull();

  const label = confirmationFormRow().querySelector('.ant-form-item-label');
  expect(label?.textContent).toContain(BILL_PAY_FIELD_LABELS.confirmPrompt.trim());
}

/**
 * Three bare presses of Enter after a preview move no money at all.
 *
 * ⚠️ Refactoring Rationale: this is the CRITICAL finding's own reproduction, transcribed from what was
 * measured in a browser. The operator typed ONLY the eleven account digits and pressed Enter three
 * times; the letter `'Y'` was never typed and nothing was clicked. Press one read the balance and then
 * moved the cursor onto the control that opened the confirmation; press two activated that control and
 * the panel's own affirmative answer took the cursor; press three activated THAT, and the whole
 * current balance was paid. Two defects composed: the screen focused a destructive control after a
 * preview, and the gate behind it also focused its affirmative answer, so "commit" was the default
 * action at two consecutive turns on a key the row-24 legend advertises as `ENTER=Continue`.
 *
 * Assumptions: the cursor may safely land on the confirmation FIELD after a preview, and that is the
 * whole distinction. `MOVE -1 TO CONFIRML` at `app/cbl/COBIL00C.cbl` L239 puts it there in the
 * reference too — but the field it points at is empty, and focus cannot type a character into it, so
 * the never-answered arm at L182-L184 is what an Enter arriving there reaches.
 *
 * Assumptions: the assertion is on the PAYING composition's call count and not merely on the absence
 * of a success sentence. A payment that was composed and then failed would leave no success sentence
 * either, and it would still have reached the service.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function threeBarePressesOfEnterMoveNoMoney(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await user.type(accountEntry(), FIXTURE_ACCOUNT_ID);
  await user.keyboard('{Enter}');
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
  expect(await turnsTaken()).toBe(1);

  // WHY : Assumptions: the one turn the three presses produced is the READ-ONLY composition carrying
  //       the account and nothing else. Both halves are asserted — the identifier, so the turn is
  //       about the record the operator named, and the argument COUNT, because the paying wrapper
  //       defaults its answer when called with one argument, so arity is the difference between a
  //       balance read and a payment.
  expect(await inquiryOfCall(0)).toBe(FIXTURE_ACCOUNT_ID);
  expect(await inquiryArgumentsOfCall(0)).toHaveLength(1);

  // WHY : Refactoring Rationale: the cursor is asserted after EVERY press, not once at the end. The
  //       measured defect was a commit control holding the cursor at presses two and three; a case
  //       that only checks the final resting place would pass against a screen that focused the
  //       affirmative at press two and happened to be re-rendered back onto the field afterwards.
  await expectCursorOnTheConfirmationField();

  await user.keyboard('{Enter}');
  await expectCursorOnTheConfirmationField();

  await user.keyboard('{Enter}');
  await expectCursorOnTheConfirmationField();

  expect(await paymentsComposed()).toBe(0);
  expect(await turnsTaken()).toBe(1);
  expect(screen.queryByRole('tooltip')).toBeNull();
  expect(screen.queryByRole('dialog')).toBeNull();

  // WHY : Assumptions: the standing offer is INTACT after the two bare presses — the balance still
  //       painted, the field still empty and still offered, the prompt still standing. The reference's
  //       never-answered arm re-reads the record and repaints exactly that, so the turn is a no-op an
  //       operator can see rather than a state they have to recover from.
  expect(paintedBalance().textContent).toBe('+0000001234.56');
  expect(accountEntry().value).toBe(FIXTURE_ACCOUNT_ID);
  expect(confirmationEntry().value).toBe('');
  expect(confirmationEntry()).toBeEnabled();
  expect(messageBand().textContent).toBe(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT.trim());
}

/**
 * The cursor a preview places lands on the confirmation field and on nothing else.
 *
 * Assumptions: this is `MOVE -1 TO CONFIRML` at `app/cbl/COBIL00C.cbl` L239 and it is asserted
 * POSITIVELY as well as negatively, because both halves are contracts. The reference does place the
 * cursor there, so a screen that left it on the account entry would have dropped the only per-field
 * signal this program produces; and a screen that placed it on a control whose activation pays is the
 * defect above.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aPreviewPlacesTheCursorOnTheConfirmationField(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitFor(
    /**
     * Waits for the recorded cursor move to be applied.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationEntry()).toHaveFocus();
    },
  );

  // WHY : Assumptions: the control the cursor arrives on is an INPUT and is empty, which is what makes
  //       the arrival harmless. Every other candidate on the screen is asserted not to hold it, so a
  //       future edit that moved the cursor onto the legend's `ENTER=Continue` control — the one that
  //       actually commits — fails here rather than in a browser.
  expect(confirmationEntry().value).toBe('');
  expect(accountEntry()).not.toHaveFocus();
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).not.toHaveFocus();
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03)).not.toHaveFocus();
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04)).not.toHaveFocus();
}

/**
 * Declining the confirmation reaches no service and destroys none of the operator's work.
 *
 * Assumptions: the decline is answered LOCALLY because the reference reaches no file either —
 * `app/cbl/COBIL00C.cbl` L178-L181 sets the error flag, which suppresses every later sentence, so the
 * turn is requestless. Sending the answer would spend a request to be told what is already known and
 * would let an unrelated transport failure paint an error on a turn the reference answers in silence.
 *
 * ⚠️ Refactoring Rationale: the operator's work SURVIVES the decline, where the reference performs
 * `CLEAR-CURRENT-SCREEN` at L179 and the migration reproduced it literally. Measured, that erased the
 * account identifier and discarded the fetched balance with no acknowledgement anywhere on screen, so
 * an operator who declined once had to retype the identifier and spend a second lookup to get back to
 * where they were. Declining a payment is a SAFE act, and a safe act must not destroy work. This is a
 * deliberate, documented divergence and it is asserted rather than absorbed.
 *
 * Assumptions: what is re-asserted afterwards is the standing PROMPT and not a cancellation
 * acknowledgement, because `app/cbl/COBIL00C.cbl` declares no such literal — its declining arm sets
 * the error flag precisely so that no sentence is emitted — and transformation rule T8 forbids putting
 * words on the screen that no line of the baseline holds. The offer genuinely does still stand, so the
 * sentence that states it is the honest one to leave standing.
 *
 * Assumptions: the call count is asserted against the count BEFORE the answer rather than against
 * zero, because the reading turn that made the payment available is itself a call. Asserting zero
 * would be asserting that the balance was never looked up.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function decliningTheConfirmationReachesNoService(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
  expect(await turnsTaken()).toBe(1);
  const paintedBefore = paintedBalance().textContent;

  await answerConfirmation(user, 'N');

  expect(await turnsTaken()).toBe(1);
  expect(await paymentsComposed()).toBe(0);

  expect(accountEntry().value).toBe(FIXTURE_ACCOUNT_ID);
  expect(paintedBalance().textContent).toBe(paintedBefore);
  expect(confirmationEntry()).toBeEnabled();
  expect(confirmationEntry().value).toBe('');
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
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
 * Assumptions: the answer travels as the character the operator TYPED, which is inside the set
 * `EVALUATE CONFIRMI` admits at L173-L176. The reference accepts either case there, and the commit
 * wrapper validates the same domain before it sends, so a non-confirming answer cannot reach this
 * composition at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function confirmingSubmitsExactlyOnePayment(): Promise<void> {
  const { paymentSuccessMessage } = await import('../screens/billPay');
  const transactionId = '0000000000000101';

  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  await answerWith(writtenPaymentOf(transactionId));
  await answerConfirmation(user, 'Y');
  await waitForBandToRead(paymentSuccessMessage(transactionId));

  expect(await paymentsComposed()).toBe(1);
  expect(await paymentOfCall(0)).toEqual([FIXTURE_ACCOUNT_ID, 'Y']);

  /*
   * WHY : Assumptions: the success sentence carries a DOUBLE SPACE after "successful." and the
   *       assertion names it. `MESSAGE_TEMPLATES.PAYMENT_SUCCESSFUL` joins a first literal that ENDS
   *       with a space to a second that BEGINS with one -- `app/cbl/COBIL00C.cbl` L527-L530 -- and the
   *       sentence reads as correct English without the second one, so a whitespace-tidying edit
   *       anywhere between the catalog and the band would be invisible to a reader. Rule T8 makes the
   *       doubled space a contract, so it is asserted on the composed sentence AND, through
   *       `waitForBandToRead`'s raw `textContent` comparison above, on what the band actually painted.
   *       Alternatives Considered: querying the band with `getByText`. Testing Library normalises a
   *       run of spaces in the CANDIDATE before comparing, so such a query cannot see the difference
   *       between one space and two and would pass against the tidied sentence.
   */
  expect(paymentSuccessMessage(transactionId)).toContain('successful.  Your Transaction ID is ');
  expect(paymentSuccessMessage(transactionId)).toBe(
    `Payment successful.  Your Transaction ID is ${transactionId}.`,
  );

  // WHY : Assumptions: the payment is withdrawn again afterwards, so the settled turn cannot be
  //       re-confirmed. `INITIALIZE-ALL-FIELDS` at L524 runs before the success sentence is composed
  //       at L527, which leaves the entry blank, the answer blank and nothing left to pay.
  expect(confirmationEntry()).toBeDisabled();
  expect(confirmationEntry().value).toBe('');
}

/**
 * A lower-case confirming answer pays, because the reference accepts both cases.
 *
 * Assumptions: `app/cbl/COBIL00C.cbl` L174-L175 is one branch with two arms, `'Y'` and `'y'`, so a
 * lower-case answer is HONOURED rather than corrected on the way in. A screen that upper-cased the
 * keystroke would agree with the reference by accident; one that filtered it to the upper-case form
 * only would refuse an answer the terminal took.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function aLowerCaseConfirmingAnswerPays(): Promise<void> {
  const { paymentSuccessMessage } = await import('../screens/billPay');
  const transactionId = '0000000000000202';

  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();
  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  await answerWith(writtenPaymentOf(transactionId));
  await answerConfirmation(user, 'y');
  await waitForBandToRead(paymentSuccessMessage(transactionId));

  expect(await paymentOfCall(0)).toEqual([FIXTURE_ACCOUNT_ID, 'y']);
}

/**
 * The reading turn is composed by a function that has no parameter a confirmation could travel in.
 *
 * ⚠️ Refactoring Rationale: this is asserted structurally now, where it used to be asserted on the
 * request body. The two turns were one call whose only difference was whether a `confirmation` member
 * was present, which made "this turn cannot move money" a property of a conditional — so the case
 * could only ever state that the conditional had gone the right way on that occasion.
 * `inquireAccountPayableBalance` takes an account identifier and nothing else, so there is no
 * parameter through which a held answer could reach a reading turn at all.
 *
 * Assumptions: absence and an explicit empty value are DIFFERENT to the service. The contract declares
 * the member optional and the service reads its absence the way the reference reads a blank `CONFIRMI`
 * at `app/cbl/COBIL00C.cbl` L182-L184 — read the account and report the balance without writing —
 * whereas a value it does not recognise is the L185-L190 refusal. `ui/tsconfig.json` sets
 * `exactOptionalPropertyTypes`, under which an explicit `undefined` and an absent member are different
 * types, and `ui/src/api/transactions.ts` composes the body under that setting.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theReportingTurnCannotCarryAConfirmation(): Promise<void> {
  const { inquireAccountPayableBalance } = await import('../api/transactions');
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  const sent = vi.mocked(inquireAccountPayableBalance).mock.calls[0];
  expect(sent).toEqual([FIXTURE_ACCOUNT_ID]);
  expect(await paymentsComposed()).toBe(0);
}

/**
 * A character outside the domain is refused in the reference's own words, and the offer survives it.
 *
 * ⚠️ Refactoring Rationale: `'Invalid value. Valid values are (Y/N)...'` at `app/cbl/COBIL00C.cbl`
 * L187 is REACHABLE again and is provoked here. It was dead code while the answer came from a dialogue
 * offering two controls: nothing but `'Y'` or `'N'` could be submitted, so no third character existed
 * to refuse. Restoring the mapset's one-position field restores the arm, and the arm is the thing that
 * tells an operator their keystroke was not understood instead of guessing at it.
 *
 * Assumptions: the reported balance and the standing offer both SURVIVE the refusal, and the reference
 * is what settles that. The arm sends the map at L190, before the balance move at L193-L194 has run,
 * so the operator keeps looking at the balance the previous turn read and the field stays open for a
 * second attempt. Withdrawing the offer here would make a typo cost the operator the lookup as well.
 *
 * Assumptions: the typed character stays IN the field. The reference re-sends the map with whatever
 * `CONFIRMI` received, so the operator can see what was not understood; clearing it would leave them
 * guessing at what they had typed.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function anInvalidAnswerIsRefusedAndTheOfferSurvives(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  await answerConfirmation(user, 'X');

  /*
   * WHY : Alternatives Considered: `expectVerbatimMessage`, rejected for the same reason as on the
   *       account refusal above -- this turn paints the sentence on the row-23 line AND as the field's
   *       help text, so an unscoped query matches two elements. `waitForBandToRead` compares the
   *       band's raw `textContent`, which is the non-collapsing comparison this sentence needs.
   */
  await waitForBandToRead(SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N);
  expect(SHARED_MESSAGE_SOURCES.INVALID_VALUE_VALID_VALUES_ARE_Y_N).toContainEqual({
    file: PROGRAM,
    lines: [187],
  });

  expect(await turnsTaken()).toBe(1);
  expect(await paymentsComposed()).toBe(0);

  expect(paintedBalance().textContent).toBe('+0000001234.56');
  expect(accountEntry().value).toBe(FIXTURE_ACCOUNT_ID);
  expect(confirmationEntry()).toBeEnabled();
  expect(confirmationEntry().value).toBe('X');

  expect(confirmationFormRow().className).toContain(ERROR_ROW_CLASS);
  expect(confirmationEntry()).toHaveAttribute('aria-invalid', 'true');
  /*
   * WHY : Trade-offs: the sentence is located inside the field's own ROW rather than asserted as the
   *       control's whole accessible description. The control is described by two elements at once --
   *       the `(Y/N)` domain hint and this refusal -- so the description is a concatenation whose
   *       order belongs to `ui/src/layout/fieldHelp.tsx` rather than to this contract. What this case
   *       is entitled to state is that the refusal is one of them and that the control announces
   *       itself described, which is what these two assertions say between them.
   */
  expect(
    within(confirmationFormRow()).getByText(SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N),
  ).toBeInTheDocument();
  expect(confirmationEntry()).toHaveAttribute('aria-describedby');

  await waitFor(
    /**
     * Waits for the recorded cursor move to be applied.
     * @returns {void} Nothing; the assertion either passes or the wait retries.
     */
    (): void => {
      expect(confirmationEntry()).toHaveFocus();
    },
  );
}

/**
 * Correcting the refused answer clears the mark the refusal left on that field alone.
 *
 * Assumptions: the field's own sentence disappears as the operator starts correcting it, exactly as
 * the account entry's does, while the row-23 line is left standing until a turn replaces it — because
 * the reference repaints that line only when it sends the map.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function correctingTheAnswerClearsItsMark(): Promise<void> {
  await answerWith(payableBalanceOf('1234.56'));
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
  await answerConfirmation(user, 'X');
  await waitForBandToRead(SHARED_MESSAGES.INVALID_VALUE_VALID_VALUES_ARE_Y_N);
  expect(confirmationFormRow().className).toContain(ERROR_ROW_CLASS);

  await user.clear(confirmationEntry());
  await user.keyboard('Y');

  expect(confirmationFormRow().className).not.toContain(ERROR_ROW_CLASS);
  expect(accountFormRow().className).not.toContain(ERROR_ROW_CLASS);
}

/**
 * The turn's busy affordance lands on the control that owns the turn, and never on the money.
 *
 * ⚠️ Refactoring Rationale: the indicator used to be a spinner on the control that PAINTED the
 * balance. Measured, that put a loading affordance on a figure — the one number the operator has to
 * read before answering — while the control they had actually operated showed nothing. The affordance
 * now travels with the turn: the account entry owns a reading turn and the confirmation field owns a
 * paying one.
 *
 * Assumptions: `aria-busy` is the assertion rather than a rendered spinner, because
 * `ui/src/layout/fieldHelp.tsx` publishes exactly that and nothing visual — a busy control is one an
 * assistive reader is told is busy, and jsdom has no layout engine to show anything else in.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theBusyAffordanceLandsOnTheControlThatOwnsTheTurn(): Promise<void> {
  const releaseReading = await deferTheReadingTurn();
  const { user } = await mountBillPay();

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);

  expect(accountEntry()).toHaveAttribute('aria-busy', 'true');
  expect(confirmationEntry()).not.toHaveAttribute('aria-busy', 'true');
  // WHY : Assumptions: the money is asserted to carry NO busy affordance and to be no control at all,
  //       which is the half of this case the finding was raised on.
  expect(paintedBalance()).not.toHaveAttribute('aria-busy', 'true');
  expect(paintedBalance().className).not.toContain('ant-btn');

  releaseReading({
    accountId: FIXTURE_ACCOUNT_ID,
    payableBalance: '1234.56',
    paid: false,
    returnMessage: BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT,
  });
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
  expect(accountEntry()).not.toHaveAttribute('aria-busy', 'true');

  const releasePaying = await deferThePayingTurn();
  await answerConfirmation(user, 'Y');

  expect(confirmationEntry()).toHaveAttribute('aria-busy', 'true');
  expect(accountEntry()).not.toHaveAttribute('aria-busy', 'true');
  expect(paintedBalance()).not.toHaveAttribute('aria-busy', 'true');

  releasePaying({
    transactionId: '0000000000000303',
    accountId: FIXTURE_ACCOUNT_ID,
    currentBalance: '0.00',
    paid: true,
    returnMessage: null,
  });
  const { paymentSuccessMessage } = await import('../screens/billPay');
  await waitForBandToRead(paymentSuccessMessage('0000000000000303'));
}

/**
 * The key whose turn is outstanding reports busy; the two keys being withheld report disabled.
 *
 * ⚠️ Refactoring Rationale: all three legend controls were greyed for the duration of a turn. That
 * conflated two different events. `ui/src/layout/PfKeyBar.tsx` records the reference behaviour: a 3270
 * ANNOUNCED a running task and withdrew nothing, so the control the operator pressed must stay present,
 * focusable and named while it declines the next press — which is what the busy channel expresses.
 * Greying it removed it from the tab order at the one moment a keyboard operator is most likely to be
 * pressing keys, and told them the key does not work when the truth is that they were early.
 *
 * Assumptions: the two withheld keys keep the DISABLED channel, and the split is the point of this
 * case. The busy channel says "the key you pressed is running"; PF3 and PF4 are not running, they are
 * unavailable because a write they have nothing to do with is in flight, and greying them is the honest
 * statement of that. Both channels decline in silence, which is asserted here by the message band still
 * reading the standing offer rather than the invalid-key sentence.
 *
 * Assumptions: `ui/src/layout/usePfKeys.ts` tests `disabled` BEFORE `busy`, so an entry declaring both
 * would resolve as disabled and this case would fail on the enabled assertion. That ordering is why the
 * screen replaced Enter's `disabled` rather than adding to it, and asserting the enabled state here is
 * what stops a future edit from reinstating the pair.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theOutstandingKeyReportsBusyAndTheWithheldKeysReportDisabled(): Promise<void> {
  const releaseReading = await deferTheReadingTurn();
  const { user } = await mountBillPay();

  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).toHaveAttribute('aria-busy', 'false');

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);

  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).toBeEnabled();
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).toHaveAttribute('aria-busy', 'true');
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03)).toBeDisabled();
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04)).toBeDisabled();

  // WHY : Assumptions: the busy control still carries its caption, so the accessible name is unchanged
  //       by the affordance. `ui/src/layout/PfKeyBar.tsx` passes the loading glyph in object form for
  //       exactly this reason, and a name that changed mid-turn would leave a screen-reader operator
  //       unable to find the control they had just pressed.
  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER).textContent).toBe(BILL_PAY_KEY_LABELS.ENTER);

  // WHY : Assumptions: a second press while the turn is outstanding takes NO further turn and paints NO
  //       sentence. Silence is the reference's behaviour — the terminal inhibited the keyboard, so the
  //       program never saw the keystroke and composed no message for it — and the count is the half of
  //       this that protects money.
  await user.keyboard('{Enter}');
  expect(await turnsTaken()).toBe(1);
  expect(messageBand().textContent).not.toBe(INVALID_KEY_PRESSED.trim());

  releaseReading({
    accountId: FIXTURE_ACCOUNT_ID,
    payableBalance: '1234.56',
    paid: false,
    returnMessage: BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT,
  });
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);

  expect(legendControl(BILL_PAY_KEY_LABELS.ENTER)).toHaveAttribute('aria-busy', 'false');
  expect(legendControl(BILL_PAY_KEY_LABELS.PFK03)).toBeEnabled();
  expect(legendControl(UNIFORM_PF_KEY_LABELS.PFK04)).toBeEnabled();
}

/**
 * The busy window is announced in words, from a region that is mounted on every turn.
 *
 * ⚠️ Refactoring Rationale: the screen reported the busy state only as `aria-busy` on the field that
 * owned the turn. That is a state with no remedy attached: an operator hears that a control is busy and
 * is told nothing about what to do. `REQUEST_IN_PROGRESS` — "Working on your request. Wait for the
 * screen to answer." — is the sentence, and it matters most on the paying turn, where the operator has
 * just committed money and both fields and both withheld keys have gone quiet at once.
 *
 * ⚠️ Assumptions: the region is asserted PRESENT AND EMPTY before any turn, and that is the load-bearing
 * half of this case rather than a completeness check. A `role="status"` element inserted at the moment
 * it acquires text is frequently not announced at all, because the assistive reader has no live region
 * to observe until the text is already in it; one present from the first render and changed in place is
 * announced. So a future edit that conditionally rendered the region would still show the right words
 * in the DOM and would say nothing out loud, which only the empty-then-filled sequence can catch.
 *
 * Assumptions: the sentence is compared against the catalog constant rather than a retyped string, so a
 * case cannot drift from the authored wording, and the region is located by the layout module's own
 * exported handle rather than a literal.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theBusyWindowIsAnnouncedInWords(): Promise<void> {
  const releaseReading = await deferTheReadingTurn();
  const { user } = await mountBillPay();

  /**
   * Reads the scoped busy region out of the rendered tree on each call.
   *
   * Assumptions: the region is re-queried rather than captured once, because the assertions below span
   * a state change and a captured node would be asserted against after it had been replaced.
   * @returns {HTMLElement} The always-mounted status region the screen announces the busy window in.
   */
  const announcement = (): HTMLElement => screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID);

  expect(announcement()).toHaveAttribute('role', 'status');
  expect(announcement().textContent).toBe('');

  await takeReportingTurn(user, FIXTURE_ACCOUNT_ID);
  expect(announcement().textContent).toBe(REQUEST_IN_PROGRESS);

  releaseReading({
    accountId: FIXTURE_ACCOUNT_ID,
    payableBalance: '1234.56',
    paid: false,
    returnMessage: BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT,
  });
  await waitForBandToRead(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT);
  expect(announcement().textContent).toBe('');

  const releasePaying = await deferThePayingTurn();
  await answerConfirmation(user, 'Y');
  expect(announcement().textContent).toBe(REQUEST_IN_PROGRESS);

  // WHY : Assumptions: the row-23 line is asserted to STILL hold the reference's own standing offer
  //       while the authored sentence is announced. The two channels are separate by design: row 23 is
  //       a parity surface under rule T8 and this screen owns no transcribed sentence for "working",
  //       so publishing the authored one there would overwrite the offer the operator is answering.
  expect(messageBand().textContent).toBe(BILL_PAY_MESSAGES.CONFIRM_TO_MAKE_A_BILL_PAYMENT.trim());

  releasePaying({
    transactionId: '0000000000000404',
    accountId: FIXTURE_ACCOUNT_ID,
    currentBalance: '0.00',
    paid: true,
    returnMessage: null,
  });
  const { paymentSuccessMessage } = await import('../screens/billPay');
  await waitForBandToRead(paymentSuccessMessage('0000000000000404'));
  expect(announcement().textContent).toBe('');
}

/**
 * The domain hint is separated from the field by a real gap, and the prompt owns its own line.
 *
 * ⚠️ Refactoring Rationale: the hint sat in the design system's `Space` primitive with a design token
 * passed as its `size`. Measured, that produced a 0.0px gap and the `(Y/N)` butted directly against
 * the control. The cause is in the pinned package: `antd/lib/_util/gapSize.js` accepts only the four
 * preset names or a number, so a custom-property reference was accepted and then dropped. `Flex`
 * assigns its `gap` straight to the CSS property, where such a reference resolves — so the assertion
 * is on WHICH primitive wraps the pair, which is the thing that decides whether the token is honoured.
 *
 * ⚠️ Refactoring Rationale: the prompt is the row's LABEL, where it was an inline block beside the
 * control. Measured at a 375px viewport, the 53-character prompt rendered as a fixed 333.7px block
 * that filled the line, so the control wrapped below it and came to rest flush at x=0 with its focus
 * ring clipped. A label owns its own line and takes the control's width with it.
 *
 * Assumptions: a gap belongs there at all because the mapset declares one — `CONFIRM` occupies column
 * 60 and the `(Y/N)` literal starts at `POS=(15,63)` on `app/bms/COBIL00.bms` L121-L125, so the
 * terminal left two character cells between them.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function theHintIsSpacedAndThePromptOwnsItsLine(): Promise<void> {
  await mountBillPay();

  const flex = confirmationEntry().closest('.ant-flex');
  expect(flex).not.toBeNull();
  expect(confirmationEntry().closest('.ant-space')).toBeNull();
  expect(flex?.textContent).toContain(BILL_PAY_CONFIRM_DOMAIN_HINT);

  const row = confirmationFormRow();
  const label = row.querySelector('.ant-form-item-label');
  const control = row.querySelector('.ant-form-item-control');
  expect(label?.textContent).toContain(BILL_PAY_FIELD_LABELS.confirmPrompt.trim());
  expect(control?.contains(confirmationEntry())).toBe(true);
  // WHY : Assumptions: the prompt is NOT inside the control wrapper, which is what makes it a label
  //       rather than an inline block that has to share the control's line.
  expect(control?.textContent).not.toContain(BILL_PAY_FIELD_LABELS.confirmPrompt.trim());
}

/**
 * Registers the confirmation cases.
 * @returns {void} Nothing; cases are registered as a side effect.
 */
function confirmationCases(): void {
  it('offers payment only from a typed answer', theConfirmationGatesThePayment);
  it('moves no money on three bare presses of Enter', threeBarePressesOfEnterMoveNoMoney);
  it(
    'places the cursor on the confirmation field after a preview',
    aPreviewPlacesTheCursorOnTheConfirmationField,
  );
  it(
    'reaches no service and destroys no work when the confirmation is declined',
    decliningTheConfirmationReachesNoService,
  );
  it('submits exactly one payment when confirmed', confirmingSubmitsExactlyOnePayment);
  it('honours a lower-case confirming answer', aLowerCaseConfirmingAnswerPays);
  it('cannot carry a confirmation on the reporting turn', theReportingTurnCannotCarryAConfirmation);
  it(
    'refuses an out-of-domain answer and keeps the offer standing',
    anInvalidAnswerIsRefusedAndTheOfferSurvives,
  );
  it('clears the answer mark when the answer is corrected', correctingTheAnswerClearsItsMark);
  it(
    'lands the busy affordance on the control that owns the turn',
    theBusyAffordanceLandsOnTheControlThatOwnsTheTurn,
  );
  it(
    'reports busy on the outstanding key and disabled on the withheld keys',
    theOutstandingKeyReportsBusyAndTheWithheldKeysReportDisabled,
  );
  it('announces the busy window in words', theBusyWindowIsAnnouncedInWords);
  it(
    'spaces the domain hint and gives the prompt its own line',
    theHintIsSpacedAndThePromptOwnsItsLine,
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
  await answerConfirmation(user, 'Y');

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

  // WHY : Refactoring Rationale: the reading turn is inspected through its ARGUMENTS rather than
  //       through a composed body, because the body is composed by the client wrapper now. The
  //       property under assertion is unchanged and is in fact stronger: the identifier is the only
  //       thing that travels, and there is no second parameter for anything else to travel in.
  expect(await inquiryOfCall(0)).toBe(FIXTURE_ACCOUNT_ID);

  const { paymentSuccessMessage } = await import('../screens/billPay');
  const transactionId = '0000000000000042';
  await answerWith(writtenPaymentOf(transactionId));
  await answerConfirmation(user, 'Y');

  // WHY : Assumptions: the settled turn is awaited through the SENTENCE it paints rather than through
  //       a call count, because the sentence is the observable an operator gets and it can only appear
  //       once the request has resolved. A count would be satisfied the moment the request left, which
  //       is before the member set under assertion has been recorded on a completed turn.
  await waitForBandToRead(paymentSuccessMessage(transactionId));

  // WHY : Assumptions: the paying turn carries the identifier and the answer and NOTHING else, which
  //       is asserted as a whole argument list rather than a member set. An extra argument -- an
  //       operator identifier, a remembered turn counter -- would be the session struct reappearing on
  //       the wire, and a list comparison fails on it where a per-member check would not.
  expect(await paymentOfCall(0)).toEqual([FIXTURE_ACCOUNT_ID, 'Y']);
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

  expect(await inquiryOfCall(0)).toBe(FIXTURE_ACCOUNT_ID);
  expect(await paymentsComposed()).toBe(0);
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
 * repository-wide search for the templated copybook finds exactly two programs that copy it,
 * `app/cbl/COACTUPC.cbl` and `app/app-transaction-type-db2/cbl/COTRTUPC.cbl` — and a search for the
 * `'*'` literal inside `COBIL00C` itself returns nothing, so the marker is unreachable from this
 * program by either route.
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
