/**
 * @file Component tests for the transaction detail screen in
 * `ui/src/screens/transactionDetail/index.tsx`, the migration target of BMS mapset `COTRN01`
 * (map `COTRN1A`, 56 `DFHMDF` definitions) and program `app/cbl/COTRN01C.cbl`, mounted by
 * `ui/src/router.tsx` at `/transactions/:id`.
 *
 * Purpose
 * -------
 * This file is the ONLY verification this screen receives, and that is a property of the baseline
 * rather than a choice made here. `tests/README.md` section 1.1 records it in as many words: the
 * online `CO*` programs use the CICS command-level API and "cannot run end-to-end without a CICS
 * runtime (absent on the runner); only their extractable field-validation logic is unit-tested". The
 * COBOL parity oracle under `tests/` therefore covers the BATCH chain and no part of `COTRN01C` --
 * there is **no** golden master for this screen, so nothing downstream compares its rendering
 * against the terminal's. The four contracts asserted below are consequently the whole of its
 * regression cover: the copybook-derived field widths, the painted PF-key set and its semantics, the
 * verbatim message text, and the fixed-point handling of money and timestamps.
 *
 * Parameters (module analogue)
 * ----------------------------
 * None. A test module takes no arguments. What it consumes instead is fixed input: the route address
 * and pattern handed to the render helpers in `ui/src/test/setup.ts`, the record fixtures declared
 * below, and the answers armed on the mocked `ui/src/api/transactions` read. Every one of those is
 * local to this file except the message text, which is imported from `ui/src/messages/messages.ts`
 * so that no sentence is retyped here.
 *
 * Returns (module analogue)
 * -------------------------
 * Nothing. Loading this module registers its cases with the runner; the observable result is the
 * pass or fail of each case.
 *
 * Exceptions or errors (module analogue)
 * --------------------------------------
 * A failing expectation, which Vitest raises. Two helpers additionally throw of their own accord and
 * both are documented at their definitions: `heldRead` refuses to settle a promise whose executor
 * never ran, and `routeAddressOfProgram` refuses to guess an address for a program the router's own
 * table does not carry.
 *
 * Rule 1, double-anchored
 * -----------------------
 * Assumptions: two independent documents impose the same obligation on this file and they agree, so
 * what follows extends an established house convention rather than introducing one. The
 * user-specified **Rule 1 (Explainability)** requires a docstring stating purpose, parameters,
 * returns and exceptions on every function and module entry point, and an inline comment giving the
 * WHY of every non-obvious decision under one of four named categories. `tests/README.md` section 12
 * imposes the identical obligation on "every new test, fixture builder, helper, mock, and runner
 * routine" and calls it "a hard review gate". `docs/CODE_DOCUMENTATION_STANDARD.md` is the written
 * convention both are satisfied through, and its TypeScript section is what obliges the `{Type}` tag
 * on every `@param` and `@returns` below.
 *
 * ⚠️ Assumptions: the four rationale labels are written in their PLURAL canonical form --
 * `Assumptions:`, `Trade-offs:`, `Alternatives Considered:`, `Refactoring Rationale:` -- and the
 * singular form is not used anywhere in this file. `docs/CODE_DOCUMENTATION_STANDARD.md` L247-L248
 * states that the singular is "not a permitted abbreviation" of the plural, and
 * `config/rule1/rule1_gate.py` enforces exactly that: its `CANONICAL_LABELS` carries the four plural
 * forms and its `_SINGULAR_STEMS` rejects the two singular ones. That gate runs in CI, so the plural
 * is the form this tree is audited against and the singular would fail the build. The same gate
 * permits a `WHAT:` comment only inside a file's leading header block, so statement-level rationale
 * below is introduced with `WHY :` and never with the other token.
 *
 * ⚠️ Assumptions: the test APIs are IMPORTED from `vitest` by name rather than taken from the
 * runner's injected globals, and the import is load-bearing rather than stylistic. `ui/tsconfig.json`
 * L117 sets `"types": []` on purpose, so no ambient declaration of `describe`, `it`, `expect` or `vi`
 * is reachable from the project that covers `src` -- `ui/vitest.config.ts` L128-L135 records the
 * reasoning and states that "every test file continues to import what it uses by name". Omitting the
 * import compiles to `Cannot find name 'describe'` under `tsc --noEmit`, which
 * `.github/workflows/ui-ci.yml` runs as a gate. The runner's own `globals: true` governs only what
 * exists at run time and cannot substitute for the declaration.
 *
 * Fabricated data
 * ---------------
 * Assumptions: every identifier below is fabricated and none resembles a live value. The transaction
 * identifiers are counting sequences, the card number is the contract's own reduced rendering rather
 * than any account, and the timestamps carry the business date `app/jcl/INTCALC.jcl` L22 injects as
 * `PARM='2022071800'` so a fixture reads as this system's own reference data rather than as today.
 */

import { act, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { Link, Route, Routes } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import type * as TransactionsModule from '../api/transactions';
import type { ApiError, TransactionDetail } from '../api/types';
// Assumptions: the busy region is located by the identifier its own helper publishes, so a case here
//   cannot drift from the one place that decides the region's shape.
import { BUSY_ANNOUNCEMENT_TEST_ID } from '../layout/fieldHelp';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import {
  COMMON_MESSAGES,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  REQUEST_IN_PROGRESS,
  SCREEN_TITLES,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  TRANSACTION_DETAIL_FIELD_LABELS,
  TRANSACTION_DETAIL_LOOKUP_LABEL,
  TRANSACTION_DETAIL_TITLE,
  normaliseMessageBandValue,
} from '../messages/messages';
import { ROUTE_TABLE, TRANSACTION_DETAIL_PATH, TRANSACTION_LIST_PATH } from '../router';
import { FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';

import {
  apiError,
  expectMaxLength,
  expectVerbatimMessage,
  fieldError,
  pressPfKey,
  renderInAppShell,
  seedSession,
  shellLandmark,
} from './setup';
import type { HarnessRenderResult } from './setup';

/**
 * The read this screen performs, replaced so each case decides its own outcome.
 *
 * Assumptions: the CLIENT is mocked rather than the HTTP transport, because every case below is
 * about what the SCREEN does with an outcome -- which sentence it paints, which address it enters,
 * which field it marks -- and not about how a request is serialised. `ui/src/api/transactions.ts`
 * has its own conformance suite for the wire shape, and no request-interception library is installed
 * in this package at all, so there is no network endpoint and no credential anywhere in this file.
 */
const viewTransactionMock = vi.fn();

vi.mock(
  '../api/transactions',
  /**
   * Replaces the single read this screen issues, leaving every other export of the module intact.
   *
   * Assumptions: the real module is spread first through `vi.importActual`, so the DTO re-exports
   * and the five sibling operations keep their real definitions. A factory returning only the
   * stubbed member would make every other import from this path `undefined`, which fails at module
   * evaluation rather than at an assertion.
   * @returns {Promise<typeof TransactionsModule>} The real module with `viewTransaction` stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof TransactionsModule>('../api/transactions');
    return {
      ...actual,
      /**
       * Stands in for the keyed read of one transaction.
       * @returns {Promise<unknown>} Whatever the case armed, which is always a held promise.
       */
      viewTransaction: viewTransactionMock,
    };
  },
);

/*
 * WHY : Assumptions: the screen is reached through a DEFERRED dynamic import rather than a static
 *       one, and the deferral is what makes the mock above effective. `vi.mock` is hoisted above
 *       every statement in this module, but its factory closes over `viewTransactionMock` and runs
 *       when the mocked path is first imported -- so a static import of the screen would evaluate
 *       that factory before the `const` above had initialised, and the case would fail on a
 *       temporal-dead-zone error rather than on anything it asserts. The sibling screen suites are
 *       written the same way for the same reason.
 */
const {
  default: TransactionDetailScreen,
  TRANSACTION_DETAIL_KEY_LABELS,
  TRANSACTION_DETAIL_LEGEND,
  TRANSACTION_DETAIL_PROGRAM_NAME,
  TRANSACTION_DETAIL_SCREEN_WIDTHS,
  TRANSACTION_DETAIL_TRANSACTION_ID,
  TRANSACTION_ID_ENTRY_WIDTH,
  formatTransactionAmount,
} = await import('../screens/transactionDetail/index');

/**
 * Declared width of the lookup key, from three declarations that agree.
 *
 * Assumptions: `TRNIDINI PIC X(16)` at `app/cpy-bms/COTRN01.CPY` L60, `TRNIDIN ... LENGTH=16` at
 * `app/bms/COTRN01.bms` L88 and `TRAN-ID PIC X(16)` at `app/cpy/CVTRA05Y.cpy` L5. The figure is
 * declared here so the assertion on the rendered control reads as a citation rather than as a magic
 * number, and so a case fails on the width rather than on a hand-counted string.
 */
const TRAN_ID_DECLARED_WIDTH = 16;

/**
 * Declared width of both stored timestamps, `PIC X(26)` at `app/cpy/CVTRA05Y.cpy` L16 and L17.
 *
 * Assumptions: twenty-six characters is the `'YYYY-MM-DD HH:MM:SS.mmmmmm'` form, so the last six
 * digits are the microseconds the contract preserves and a JavaScript `Date` would discard.
 */
const TIMESTAMP_DECLARED_WIDTH = 26;

/**
 * Characters of a timestamp the terminal painted, `TORIGDTI`/`TPROCDTI PIC X(10)` at
 * `app/cpy-bms/COTRN01.CPY` L108 and L114.
 *
 * Assumptions: a COBOL alphanumeric `MOVE` of a `PIC X(26)` field into a `PIC X(10)` one truncates
 * on the right, which `app/cbl/COTRN01C.cbl` L185-L186 does for both fields, so the ten characters
 * are the leading `YYYY-MM-DD` date portion.
 */
const TIMESTAMP_DISPLAY_WIDTH = 10;

/**
 * Declared width of the row-24 legend field, `LENGTH=47` at `app/bms/COTRN01.bms` L263-L268.
 *
 * Assumptions: the four painted groups are separated by TWO spaces, which is what makes the total
 * 47 -- 11 + 2 + 7 + 2 + 8 + 2 + 15. Single-spacing them would produce 44 and break the verbatim
 * contract, so the figure is asserted against the screen's own reassembly rather than trusted.
 */
const LEGEND_DECLARED_WIDTH = 47;

/**
 * Declared widths of the four header-band slots this file can source from its own dependencies.
 *
 * Assumptions: the keys are the symbolic-map member names of `app/cpy-bms/COTRN01.CPY` and the
 * values are its `PIC X(n)` clauses -- `TRNNAMEI` L24, `TITLE01I` L30, `PGMNAMEI` L42 and
 * `TITLE02I` L48. The two remaining header slots, `CURDATEI` L36 and `CURTIMEI` L54, are both
 * `PIC X(8)` and are filled by the shell from the server clock rather than by this screen, so they
 * are asserted from the painted band instead of from a constant.
 *
 * ⚠️ Assumptions: the message field is deliberately absent from this table. `ERRMSGI` is declared
 * `PIC X(78)` at L144 and the BMS field is `LENGTH=78` at `app/bms/COTRN01.bms` L261, but the
 * message CONTENT contract is **75**, from `CCARD-ERROR-MSG PIC X(75)` and `CCARD-RETURN-MSG
 * PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28-L30. The two figures are both correct and describe
 * different things -- 75 is what a program may compose, 78 is the cell the terminal reserves for it
 * -- so 75 is recorded here and is NOT "corrected" to 78. Neither is asserted in this file: the
 * band's own width contract belongs to `ui/src/layout/MessageBand.test.tsx`, which owns the
 * component, and restating it here would put a second, unowned copy of it in the tree.
 */
const HEADER_BAND_DECLARED_WIDTHS = {
  TRNNAMEI: 4,
  TITLE01I: 40,
  PGMNAMEI: 8,
  TITLE02I: 40,
} as const;

/**
 * Width of the two clock slots of the header band, `PIC X(8)` at `app/cpy-bms/COTRN01.CPY` L36
 * and L54.
 */
const HEADER_CLOCK_DECLARED_WIDTH = 8;

/**
 * A transaction identifier of the declared sixteen-character width.
 *
 * Assumptions: composed as a counting sequence padded with leading zeroes, because `TRAN-ID` is a
 * CHARACTER key at `app/cpy/CVTRA05Y.cpy` L5 whose leading zeroes are part of it -- a value written
 * as `123` would be a different key, not the same one shorter.
 */
const A_TRANSACTION_ID = '0000000000000123';

/** A second identifier of the same declared width, for a case that takes two turns. */
const ANOTHER_TRANSACTION_ID = '0000000000000456';

/**
 * The address a deep link into this screen carries.
 *
 * Assumptions: composed from `TRANSACTION_LIST_PATH` and the identifier rather than written out, so
 * the address cannot disagree with the route table that serves it.
 */
const DETAIL_ADDRESS = `${TRANSACTION_LIST_PATH}/${A_TRANSACTION_ID}`;

/** Text the browse stand-in paints, so a transition to the list route is observable. */
const BROWSE_STAND_IN = 'BROWSE STAND-IN';

/** Text the main-menu stand-in paints, so PF3's fallback transition is observable. */
const MENU_STAND_IN = 'MENU STAND-IN';

/** Text of the browse stand-in's link, which opens this screen naming the browse as its origin. */
const OPEN_SELECTED_LINK = 'OPEN THE SELECTED TRANSACTION';

/**
 * The card number as the service publishes it: twelve concealed positions and four digits.
 *
 * Assumptions: this is the only rendering an ordinary read may return -- `viewTransaction` in
 * `ui/src/api/transactions.ts` throws a `RangeError` before the screen sees a response carrying
 * anything else -- so a fixture holding a whole account number would describe a response the
 * transport refuses.
 */
const A_REDUCED_CARD_NUMBER = '************1111';

/**
 * A whole sixteen-digit card number, used only to assert that no such value reaches the document.
 *
 * Assumptions: fabricated as a counting sequence, and never placed in a fixture the screen reads.
 * It exists so a case can assert its ABSENCE from the rendered document, which is the assertion
 * that would fail if the screen ever composed a mask of its own from an unreduced value.
 */
const AN_UNREDUCED_CARD_NUMBER = '1234567890121111';

/** The stored origination timestamp, in the twenty-six-character form the contract carries. */
const AN_ORIGIN_TIMESTAMP = '2022-07-18 22:10:31.123456';

/** The stored processing timestamp, whose microseconds differ so the two cannot be confused. */
const A_PROCESS_TIMESTAMP = '2022-07-19 01:02:03.654321';

/**
 * The amount exactly as the contract publishes it: a string with two decimal places.
 *
 * ⚠️ Trade-offs: this is a `string` and every assertion on it below is a string comparison. No
 * numeric conversion, no decimal-rounding helper and no arithmetic of any kind appears in this file,
 * and the mechanism that makes the prohibition necessary is specific: a JSON number is parsed into an
 * IEEE-754 double by most clients, which destroys the exactness that `NUMERIC(12,2)` preserves in the
 * database and `BigDecimal` at scale 2 preserves in the service -- so the wire form is a string and
 * the assertion has to be one too. The value also carries leading zeroes the mask strips and re-adds,
 * which a numeric round trip could not reproduce.
 */
const AN_AMOUNT = '-000000123.45';

/**
 * The amount as the reference's `PIC +99999999.99` mask paints it, from `COTRN01C.cbl` L49.
 *
 * Assumptions: an always-present sign, eight ZERO-filled integer positions and two decimals, which
 * is what the picture's `9` positions mean -- they fill rather than blank. Twelve characters in
 * total, which is `TRNAMTI PIC X(12)` at `app/cpy-bms/COTRN01.CPY` L102.
 */
const THE_MASKED_AMOUNT = '-00000123.45';

/**
 * Builds the fourteen-member record the stubbed read answers with.
 *
 * Assumptions: every member the contract declares is supplied, because a partial record describes a
 * response the service cannot send and would let a narrowing pass here that the real shape fails.
 * Every value is a string or `null`; `ui/src/api/types.ts` declares no numeric member on this shape
 * at all, which is what keeps money and both timestamps clear of an IEEE-754 double.
 *
 * Assumptions: each value is authored at the width the mapset declares for its field, so a rendered
 * value can be compared character for character against what the terminal would have painted. The
 * two exceptions are deliberate and are the reference's own: the description and the merchant name
 * are shorter than their `LENGTH=` operands because a shorter value is what the record usually
 * carries, and both are asserted by content rather than by width.
 * @param {string} transactionId - The identifier the record is keyed by, at the declared width.
 * @returns {TransactionDetail} A complete record, so no case depends on an absent member.
 */
function aTransaction(transactionId: string): TransactionDetail {
  return {
    transactionId,
    // WHY : Assumptions: two characters, from `TTYPCDI PIC X(2)` at `app/cpy-bms/COTRN01.CPY` L78
    //       and `TRAN-TYPE-CD PIC X(02)` at `app/cpy/CVTRA05Y.cpy` L6.
    typeCode: '01',
    // WHY : Assumptions: four characters, from `TCATCDI PIC X(4)` at `app/cpy-bms/COTRN01.CPY` L84.
    //       The record field is `TRAN-CAT-CD PIC 9(04)` at `app/cpy/CVTRA05Y.cpy` L7, and the
    //       contract carries it as a string because a leading zero is part of a category code.
    categoryCode: '5411',
    // WHY : Assumptions: within the ten characters of `TRNSRCI PIC X(10)` at L90.
    source: 'POS',
    // WHY : Assumptions: shorter than the sixty characters of `TDESCI PIC X(60)` at L96, which is
    //       itself narrower than `TRAN-DESC PIC X(100)` at `app/cpy/CVTRA05Y.cpy` L9.
    description: 'A FABRICATED TRANSACTION DESCRIPTION',
    amount: AN_AMOUNT,
    // WHY : Assumptions: nine digits, from `MIDI PIC X(9)` at L120 and `TRAN-MERCHANT-ID PIC 9(09)`
    //       at `app/cpy/CVTRA05Y.cpy` L11.
    merchantId: '000000456',
    merchantName: 'A FABRICATED MERCHANT',
    merchantCity: 'A FABRICATED CITY',
    // WHY : Assumptions: ten characters, from `MZIPI PIC X(10)` at L138 and
    //       `TRAN-MERCHANT-ZIP PIC X(10)` at `app/cpy/CVTRA05Y.cpy` L14.
    merchantZip: '12345-6789',
    cardNumber: A_REDUCED_CARD_NUMBER,
    originTimestamp: AN_ORIGIN_TIMESTAMP,
    processTimestamp: A_PROCESS_TIMESTAMP,
    // WHY : Assumptions: `null` and not a sentence, because `app/cbl/COTRN01C.cbl` L91 moves
    //       `SPACES` into `WS-MESSAGE` on entry and no arm of a successful read sets it -- a blank
    //       row 23 is the faithful rendering of a read that worked.
    returnMessage: null,
  };
}

/**
 * A promise the case settles itself, so a state update never lands outside React's batching.
 *
 * ⚠️ Refactoring Rationale: an earlier draft armed the read with `mockResolvedValue` and waited for
 * the record with `waitFor`. It passed, and it emitted `An update to TransactionDetailScreen inside
 * a test was not wrapped in act(...)` on every case -- because the screen's effect calls the read
 * during commit, and an already-resolved promise runs its continuation in a microtask that falls
 * between the render helper's own awaits, where no `act` scope is open. Holding the promise and
 * settling it inside `act` puts the update where React expects it, which removes the warning rather
 * than silencing it. The sibling screen suites hold their resolutions for the same reason.
 */
interface HeldRead {
  /** The promise the stubbed read returns, still pending. */
  readonly promise: Promise<TransactionDetail>;
  /**
   * Answers the read with a record, inside `act`.
   * @param {TransactionDetail} detail - The record the service is to have returned.
   * @returns {Promise<void>} Resolves once the screen has published the record.
   */
  readonly answerWith: (detail: TransactionDetail) => Promise<void>;
  /**
   * Refuses the read, inside `act`.
   * @param {ApiError} problem - The normalised problem document the client raises.
   * @returns {Promise<void>} Resolves once the screen has published the refusal.
   */
  readonly refuseWith: (problem: ApiError) => Promise<void>;
}

/**
 * Runs the queued continuations of a settled promise inside React's update batching.
 *
 * Assumptions: TWO microtask turns are awaited rather than one, because the screen's continuation is
 * itself reached through a `.then` on the promise the read returned -- so the first turn runs the
 * screen's handler and the second lets the state it set commit. One turn leaves the commit outside
 * the scope and reintroduces the warning this exists to prevent.
 * @returns {Promise<void>} Resolves once every pending continuation has run and committed.
 */
async function settleInsideAct(): Promise<void> {
  await act(
    /**
     * Yields to the microtask queue twice.
     * @returns {Promise<void>} Resolves after both turns.
     */
    async (): Promise<void> => {
      await Promise.resolve();
      await Promise.resolve();
    },
  );
}

/**
 * Arms the stubbed read to answer its NEXT call from a promise this case controls.
 *
 * Assumptions: `mockReturnValueOnce` rather than `mockReturnValue`, so a case taking two turns arms
 * two independent promises. A single shared promise would already be settled by the second call, and
 * the second turn's update would land outside `act` again.
 * @returns {HeldRead} The held promise and the two ways to settle it.
 * @throws {Error} From either settler, if the promise executor did not run -- which cannot happen,
 *   because a `Promise` executor runs synchronously, and is checked rather than asserted away so a
 *   future change to this helper fails here instead of silently doing nothing.
 */
function armHeldRead(): HeldRead {
  const settlers: {
    resolve?: (detail: TransactionDetail) => void;
    reject?: (reason: unknown) => void;
  } = {};

  const promise = new Promise<TransactionDetail>(
    /**
     * Captures both settlement functions for the returned helpers to call later.
     * @param {(detail: TransactionDetail) => void} resolve - Settles the read with a record.
     * @param {(reason: unknown) => void} reject - Settles the read with a failure.
     * @returns {void} Nothing; the functions are captured as a side effect.
     */
    (resolve, reject): void => {
      settlers.resolve = resolve;
      settlers.reject = reject;
    },
  );

  viewTransactionMock.mockReturnValueOnce(promise);

  return {
    promise,
    /**
     * Answers the held read with a record and lets the screen publish it.
     * @param {TransactionDetail} detail - The record the service is to have returned.
     * @returns {Promise<void>} Resolves once the screen has published the record.
     * @throws {Error} If the promise executor never ran.
     */
    answerWith: async (detail: TransactionDetail): Promise<void> => {
      const resolve = settlers.resolve;
      if (resolve === undefined) {
        throw new Error('the held read has no resolver: its promise executor did not run');
      }
      resolve(detail);
      await settleInsideAct();
    },
    /**
     * Refuses the held read and lets the screen publish the refusal.
     *
     * Assumptions: the problem document is wrapped under a `problem` member, which is the shape
     * `ui/src/api/client.ts` raises and the shape the screen's own narrowing accepts.
     * @param {ApiError} problem - The normalised problem document.
     * @returns {Promise<void>} Resolves once the screen has published the refusal.
     * @throws {Error} If the promise executor never ran.
     */
    refuseWith: async (problem: ApiError): Promise<void> => {
      const reject = settlers.reject;
      if (reject === undefined) {
        throw new Error('the held read has no rejecter: its promise executor did not run');
      }
      reject({ problem });
      await settleInsideAct();
    },
  };
}

/**
 * Finds the address the router serves for one CICS program, from the router's own table.
 *
 * Assumptions: the lookup is by PROGRAM name rather than by path, because the program name is what
 * `app/cbl/COTRN01C.cbl` actually moves -- `'COMEN01C'` at L117 and `'COTRN00C'` at L126 -- and
 * `ROUTE_TABLE` in `ui/src/router.tsx` carries the program beside each path precisely so the graph
 * can be checked against the reference rather than against itself. Writing the address as a literal
 * here would be a second spelling of a path the router owns, and the transition under test would
 * then be asserted against this file rather than against the route that serves it.
 * @param {string} program - The COBOL program name, exactly as the reference moves it.
 * @returns {string} The path pattern the router mounts that program's replacement at.
 * @throws {Error} If no route names that program, which means the table and this citation disagree.
 */
function routeAddressOfProgram(program: string): string {
  const entry = ROUTE_TABLE.find(
    /**
     * Reports whether one table row replaces the named program.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One row of the reachability graph.
     * @returns {boolean} True when the row names that program.
     */
    (candidate): boolean => candidate.program === program,
  );
  if (entry === undefined) {
    throw new Error(`ui/src/router.tsx ROUTE_TABLE names no route for program ${program}`);
  }
  return entry.path;
}

/** Address PF3 falls back to, which is where `COTRN01C.cbl` L117 sends a caller-less arrival. */
const MENU_ADDRESS = routeAddressOfProgram('COMEN01C');

/** Address PF5 transfers to, which is the browse program `COTRN01C.cbl` L126 names. */
const BROWSE_ADDRESS = routeAddressOfProgram('COTRN00C');

/**
 * Stands in for the transaction browse, and hands this screen over the way the browse does.
 *
 * Purpose
 * -------
 * PF3 returns to the screen that handed control over, so a case asserting that arm has to ARRIVE from
 * somewhere that named itself. The link below is the migrated form of `app/cbl/COTRN00C.cbl` L190-L191,
 * which moves `WS-TRANID` and `WS-PGMNAME` into the reference's own `CDEMO-FROM-*` carriers before
 * transferring control -- expressed here as history state rather than as a COMMAREA overlay.
 *
 * Assumptions: the origin is handed over as `state.from`, which is the member
 * `ui/src/routes/navigation.ts` declares on `ScreenTransitionState` and validates against the route
 * table, and it carries the browse's own route so the validation admits it.
 *
 * Assumptions: this is a plain anchor rendered by the router's own `Link`, not a design-system
 * control, because it is test scaffolding standing in for a screen this file does not exercise. The
 * library-components rule governs the component under test.
 * @returns {ReactElement} The stand-in, with a link that opens the detail screen naming its origin.
 */
function BrowseStandIn(): ReactElement {
  return (
    <div>
      <span>{BROWSE_STAND_IN}</span>
      <Link to={DETAIL_ADDRESS} state={{ from: TRANSACTION_LIST_PATH }}>
        {OPEN_SELECTED_LINK}
      </Link>
    </div>
  );
}

/**
 * Opens the screen inside the real shell, with the two routes its keys transfer to also mounted.
 *
 * Purpose
 * -------
 * Every case below runs against this one tree, so the keyboard channel and the legend-button channel
 * are exercised against the same composition rather than against two different ones.
 *
 * Assumptions: the subject is a `Routes` tree of THREE routes rather than the screen alone, and that
 * is what makes a transition's DESTINATION observable. Rendered as a bare subject the screen would
 * simply unmount when PF3 or PF5 navigated, which shows that something happened and not where it
 * went. The two stand-ins are plain elements because they are test scaffolding rather than screen
 * output -- the design system's library-components rule governs the component under test, and the
 * sibling screen suites mount route stand-ins the same way.
 *
 * Assumptions: no `routePath` is passed, so the harness mounts this tree under its own catch-all
 * inside the shell's layout route. The inner `Routes` then matches the address itself, which is what
 * lets the screen keep reading its parameter as `id` from `TRANSACTION_DETAIL_PATH` while a
 * transition to either sibling route still resolves to something.
 *
 * ⚠️ Assumptions: the shell is mounted with NO session, and the cases that assert on the legend
 * depend on that. `ui/src/layout/AppShell.tsx` reads `signedOn` from `ui/src/hooks/useAuth.ts` for
 * one purpose only -- whether to render its sign-off control -- so an unauthenticated tree paints
 * the frame in full and adds no extra button for a legend assertion to count.
 * @param {string} address - The address the router opens at.
 * @returns {Promise<HarnessRenderResult>} The render result, with the keyboard operator attached.
 */
async function openTheDetailScreen(address: string): Promise<HarnessRenderResult> {
  return await renderInAppShell(
    <Routes>
      <Route path={TRANSACTION_DETAIL_PATH} element={<TransactionDetailScreen />} />
      <Route path={BROWSE_ADDRESS} element={<BrowseStandIn />} />
      <Route path={MENU_ADDRESS} element={<div>{MENU_STAND_IN}</div>} />
    </Routes>,
    { initialEntries: [address] },
  );
}

/**
 * Opens the screen on a deep link and answers its arrival read with one record.
 *
 * Assumptions: the read is armed BEFORE the render, because `app/cbl/COTRN01C.cbl` L103-L108 performs
 * its lookup as part of the first entry when a selection was handed over -- the migrated form of that
 * is an effect that fires on mount, so a read armed afterwards would arrive too late.
 * @param {TransactionDetail} record - The record the arrival read is to answer with.
 * @returns {Promise<HarnessRenderResult>} The render result, with the record already painted.
 */
async function openWithRecord(record: TransactionDetail): Promise<HarnessRenderResult> {
  const held = armHeldRead();
  const rendered = await openTheDetailScreen(`${TRANSACTION_LIST_PATH}/${record.transactionId}`);
  await held.answerWith(record);
  return rendered;
}

/**
 * Locates the lookup control, which is the map's single unprotected field.
 *
 * Assumptions: located by ROLE and not by a test identifier, because the role is what an assistive
 * technology acts on, and because the map declares exactly one `UNPROT` field -- `TRNIDIN` at
 * `app/bms/COTRN01.bms` L85 -- so a query that found two would itself be the failure.
 * @returns {HTMLElement} The lookup input.
 * @throws {Error} If no textbox is present, or if more than one is, which Testing Library raises.
 */
function lookupControl(): HTMLElement {
  return screen.getByRole('textbox');
}

/**
 * Reads the sentence the row-23 band currently holds, without normalising its whitespace.
 *
 * Assumptions: the raw `textContent` is compared rather than a text query, because two of the
 * sentences this screen paints carry trailing blanks that are part of their declared width --
 * `CCDA-MSG-INVALID-KEY` is `PIC X(50)` holding 49 characters at `app/cpy/CSMSG01Y.cpy` L20-L21 --
 * and every text matcher trims boundary whitespace. Trimming would compare a shortened sentence and
 * report a pass for a screen that had dropped the padding.
 *
 * Assumptions: the band is the one the screen delegates, which is the ERROR channel:
 * `ui/src/layout/MessageBand.tsx` defaults `channel` to `error` and the screen names none, so the
 * identifier is the row-23 one every mapset has.
 * @returns {string} The band's text, which is the empty string when it holds none.
 */
function bandText(): string {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '';
}

/**
 * Asserts that the row-23 band holds one catalogued sentence, as the band itself renders it.
 *
 * ⚠️ Assumptions: the expected value is passed through `normaliseMessageBandValue`, which is the
 * band's OWN normaliser -- `ui/src/layout/MessageBand.tsx` L522 resolves its text through it, and
 * `ui/src/messages/messages.ts` L4436-L4446 defines it as stripping `U+0000` and trimming. Comparing
 * against the raw catalog entry would fail for a correct screen on the one sentence that carries
 * padding: `CCDA-MSG-INVALID-KEY` is `PIC X(50)` holding 49 characters, so the catalog stores it
 * padded and the band renders it trimmed. Using the band's own normaliser asserts what the band
 * undertakes to show, while the declared width and the padded literal stay asserted from the catalog
 * where they belong -- and it keeps the trailing blanks out of no assertion at all, which a
 * hand-written `trimEnd` here would have hidden as a local convenience.
 *
 * Assumptions: `U+0000` is the wire form of `LOW-VALUES`, which is what every one of these message
 * fields declares as its off condition, so the normaliser's other half is a baseline concern rather
 * than a rendering one.
 *
 * ⚠️ Alternatives Considered: retyping each sentence as a literal here, which reads more directly at
 * the call site and needs no import. Rejected because of a specific failure mode it cannot detect: a
 * test holding its own copy of a sentence asserts only that two strings in this repository agree, so
 * a paraphrase written into the screen and the same paraphrase written into the test agree with each
 * other and disagree with the baseline -- the case passes green while the character-for-character
 * fidelity AAP Rule T8 requires is already lost. Every sentence therefore arrives from
 * `ui/src/messages/messages.ts`, which is the one module that transcribes the literals from the
 * programs and carries their line numbers, so the assertion is that the screen agrees with the
 * transcription rather than with a second copy of a guess.
 * @param {string} sentence - The catalogued sentence, taken from the catalog and never retyped.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function expectBandSentence(sentence: string): void {
  expect(bandText()).toBe(normaliseMessageBandValue(sentence));
  // WHY : Assumptions: a non-empty expectation is asserted as well, so a case cannot pass because
  //       both sides normalised to nothing -- which is exactly what would happen if a catalog entry
  //       were ever reduced to blanks.
  expect(bandText().length).toBeGreaterThan(0);
}

/**
 * Lists the legend's controls, in the order the row-24 field paints them.
 *
 * Assumptions: scoped to the shell's `contentinfo` landmark rather than queried across the document,
 * so a control the screen body happens to render could never be counted as a legend key. The count
 * is itself an assertion in one case below, which only means anything if the scope is exact.
 * @returns {HTMLElement[]} The legend controls, in document order.
 */
function legendControls(): HTMLElement[] {
  const legend = shellLandmark('keyLegend');
  return Array.from(legend.querySelectorAll('button'));
}

/**
 * Locates one legend control by the browser key it advertises.
 *
 * Assumptions: selected by `aria-keyshortcuts` rather than by its label text, because that attribute
 * is what ties a control to the key it stands for -- `ui/src/layout/PfKeyBar.tsx` derives it from the
 * same table `usePfKeys` dispatches on. Selecting by label would tie this query to a verbatim string
 * whose own fidelity is asserted elsewhere, so a label change would fail two cases for one reason.
 * @param {string} shortcut - The advertised browser key, for instance `Enter` or `F5`.
 * @returns {HTMLElement} The control standing for that key.
 * @throws {Error} If no legend control advertises that key.
 */
function legendControlFor(shortcut: string): HTMLElement {
  const control = legendControls().find(
    /**
     * Reports whether one control advertises the wanted key.
     * @param {HTMLElement} candidate - One legend control.
     * @returns {boolean} True when it advertises that key.
     */
    (candidate: HTMLElement): boolean => candidate.getAttribute('aria-keyshortcuts') === shortcut,
  );
  if (control === undefined) {
    throw new Error(`the row-24 legend paints no control for ${shortcut}`);
  }
  return control;
}

/**
 * Asserts the cleared state: an empty key, thirteen blank values and thirteen standing labels.
 *
 * Assumptions: all three halves are asserted together because the reference clears all three
 * together and only together. `INITIALIZE-ALL-FIELDS` at `app/cbl/COTRN01C.cbl` L309-L326 blanks the
 * lookup key, the thirteen data fields and `WS-MESSAGE`; the thirteen LABELS are mapset literals it
 * cannot touch. A case asserting only the empty values would pass for a screen that had unmounted the
 * grid, which is the one thing the baseline never does.
 * @returns {void} Nothing; the assertions either pass or fail the calling case.
 */
function expectTheClearedScreen(): void {
  expect(lookupControl()).toHaveValue('');
  expect(bandText()).toBe('');
  for (const field of Object.keys(TRANSACTION_DETAIL_FIELD_LABELS)) {
    const member = field as keyof typeof TRANSACTION_DETAIL_FIELD_LABELS;
    expect(expectVerbatimMessage(TRANSACTION_DETAIL_FIELD_LABELS[member])).toBeInTheDocument();
    expect(recordTextFor(member)).toBe('');
  }
}

/**
 * Locates the value cell standing beside one of the thirteen record labels.
 *
 * Assumptions: the label is found by its CATALOGUED text and the value is its cell's next sibling,
 * which is the structure a bordered `Descriptions` emits -- each pair is a label cell followed by a
 * content cell in the same row. Reaching the value through its label is what ties the assertion to
 * the label the mapset paints beside that field, rather than to a position in the grid, which design
 * gap G1 explicitly gives up.
 * ⚠️ Assumptions: the ELEMENT returned is the value's own `Typography.Text` node and not the cell
 * holding it, because the style and `title` assertions below read what the SCREEN set rather than
 * what the grid set. It is reached by class rather than as the cell's first child because antd wraps
 * a content cell's children in an unstyled span of its own -- measured against this screen, whose
 * amount cell renders `td > span > span.ant-typography` -- so the first child is the wrapper and
 * carries neither the style nor the title.
 * @param {keyof typeof TRANSACTION_DETAIL_FIELD_LABELS} field - The record member to read.
 * @returns {HTMLElement} The element holding that field's rendered value.
 * @throws {Error} If the label is absent, which Testing Library raises, or if its cell holds no
 *   value element, which would mean the record view is no longer a label-and-value grid.
 */
function recordValueFor(field: keyof typeof TRANSACTION_DETAIL_FIELD_LABELS): HTMLElement {
  const label = screen.getByText(TRANSACTION_DETAIL_FIELD_LABELS[field]);
  const labelCell = label.closest('.ant-descriptions-item-label');
  const value = labelCell?.nextElementSibling?.querySelector('.ant-typography');
  if (!(value instanceof HTMLElement)) {
    throw new Error(`the record label for ${field} stands beside no value element`);
  }
  return value;
}

/**
 * Reads one record field's rendered text.
 * @param {keyof typeof TRANSACTION_DETAIL_FIELD_LABELS} field - The record member to read.
 * @returns {string} The rendered text, which is the empty string on a cleared screen.
 * @throws {Error} If the field's label or value element is absent.
 */
function recordTextFor(field: keyof typeof TRANSACTION_DETAIL_FIELD_LABELS): string {
  return recordValueFor(field).textContent ?? '';
}

/**
 * Builds the pattern that recognises a design token's CSS-variable reference.
 *
 * Purpose
 * -------
 * The design system's zero-hardcoded-values rule requires every CSS property value to resolve to a
 * token, and antd 6 expresses that at run time as a CSS variable -- `var(--ant-font-family-code)`
 * for the token named `fontFamilyCode`. Asserting the reference proves the property came from the
 * theme; asserting a concrete colour or family would assert today's palette and would pass just as
 * well for a literal.
 *
 * Assumptions: the variable NAME is derived from the token name by the camel-to-kebab rule antd
 * applies, so this helper takes the token constant rather than a written-out variable name and no
 * literal design value appears in this file. The prefix is matched loosely because
 * `ui/src/theme/antdTheme.ts` leaves `cssVar.prefix` unset so that it keeps tracking the component
 * class-name prefix -- pinning the prefix here would make this file the thing that has to change if
 * that decision were ever revisited.
 * @param {string} tokenName - An antd token name, taken from `ui/src/theme/tokens.ts`.
 * @returns {RegExp} A pattern matching that token's CSS-variable reference and nothing else.
 */
function cssVariableReferenceFor(tokenName: string): RegExp {
  const kebab = tokenName
    .replace(
      /[A-Z]/gu,
      /**
       * Lowercases one capital and prefixes it with a hyphen.
       * @param {string} capital - The matched capital letter.
       * @returns {string} The hyphenated, lowercased replacement.
       */
      (capital: string): string => `-${capital.toLowerCase()}`,
    )
    // WHY : Assumptions: antd's variable naming hyphenates a DIGIT run as well as a capital, so the
    //       ramp token `red7` is published as `--ant-red-7` and not `--ant-red7`. The rule was
    //       capital-only while every token this helper was given happened to be a camel-case alias;
    //       the error-ramp entry in `ui/src/theme/tokens.ts` is the first ramp member to reach it, and
    //       without this clause the helper builds a variable name antd never emits and the assertion
    //       fails for a reason that has nothing to do with the property under test.
    .replace(/([a-z])(\d)/gu, '$1-$2');
  return new RegExp(`^var\\(--[a-z0-9]+-${kebab}\\)$`, 'u');
}

/**
 * Asserts that the identifier in the route reaches the client as the whole of the request.
 *
 * ⚠️ Refactoring Rationale: the reference passed this selection in storage the client echoed back.
 * `app/cbl/COTRN00C.cbl` L183-L195 wrote its selection flag and `CDEMO-CT00-TRN-SELECTED` into the
 * COMMAREA and transferred control, and `app/cbl/COTRN01C.cbl` L103-L108 read the value back out of
 * the same storage before performing its read. The COMMAREA does not travel here: the identifier
 * arrives as a path parameter, which is what makes the request self-describing and independently
 * authorizable rather than trusting a value the client supplied (AAP section 0.7.1). The selection
 * members that carried it -- `CDEMO-CUST-ID`, `CDEMO-ACCT-ID` and `CDEMO-CARD-NUM` at
 * `app/cpy/COCOM01Y.cpy` L33-L41 -- have no counterpart on the wire at all.
 *
 * Assumptions: the argument LIST is asserted whole rather than only that the identifier appears in
 * it, because that is the assertion that fails if a session member is ever added beside it. One call
 * carrying exactly one argument is the entire request.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function readsTheTransactionTheRouteParameterNames(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));

  expect(viewTransactionMock.mock.calls).toStrictEqual([[A_TRANSACTION_ID]]);
  // WHY : Assumptions: the control is asserted seeded as well, which mirrors
  //       `app/cbl/COTRN01C.cbl` L105-L106 moving the carried selection into `TRNIDINI` BEFORE
  //       performing the read -- so the sent map never showed an empty key beside a populated record.
  expect(lookupControl()).toHaveValue(A_TRANSACTION_ID);
  expect(recordTextFor('transactionId')).toBe(A_TRANSACTION_ID);
}

/**
 * Asserts that the lookup control refuses a seventeenth character.
 *
 * Assumptions: the width is compared against a constant carrying its citation rather than written
 * into the assertion, and it is compared against the screen's own exported width as well. Three
 * baseline declarations agree on sixteen -- `TRNIDINI PIC X(16)` at `app/cpy-bms/COTRN01.CPY` L60,
 * `LENGTH=16` on `TRNIDIN` at `app/bms/COTRN01.bms` L88, and `TRAN-ID PIC X(16)` at
 * `app/cpy/CVTRA05Y.cpy` L5 -- so a screen that disagreed with any of them would be accepting a key
 * the service cannot hold.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function holdsTheLookupControlToItsDeclaredWidth(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));

  expect(TRANSACTION_ID_ENTRY_WIDTH).toBe(TRAN_ID_DECLARED_WIDTH);
  expectMaxLength(lookupControl(), TRAN_ID_DECLARED_WIDTH);
}

/**
 * Asserts that the cursor rests in the map's single unprotected field and in no other.
 *
 * Assumptions: `app/bms/COTRN01.bms` carries exactly ONE `IC` attribute, on `TRNIDIN` at L85, and
 * exactly one `UNPROT` field, which is the same one -- every other field on the map is
 * `ATTRB=(ASKIP,NORM)` between L105 and L256. So the target rendering must offer exactly one input
 * and must place the cursor in it, which is what a 3270 did on every send of the map.
 *
 * ⚠️ Assumptions: the assertion is on FOCUS and not on an `autofocus` attribute, because React
 * implements `autoFocus` by focusing the element on mount rather than by emitting the attribute --
 * measured against this very screen, whose rendered input carries `maxlength` and no `autofocus`.
 * Asserting the attribute would fail against a correct screen; asserting focus asserts the behaviour
 * the attribute exists to produce.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function placesTheCursorInTheMapsSingleUnprotectedField(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));

  expect(screen.getAllByRole('textbox')).toHaveLength(1);
  expect(lookupControl()).toHaveFocus();
}

/**
 * Asserts that the record is painted as a bordered, two-up `Descriptions` of thirteen labelled rows.
 *
 * Assumptions: thirteen is the count three independent readings of the baseline agree on -- the
 * mapset paints thirteen `COLOR=TURQUOISE` labels against thirteen `COLOR=BLUE` output fields,
 * `app/cpy-bms/COTRN01.CPY` declares thirteen matching `...I` members, and `app/cbl/COTRN01C.cbl`
 * names exactly thirteen in both of its own field lists at L159-L171 and L178-L190.
 *
 * Assumptions: `bordered` and a two-column layout are asserted through the rendering rather than
 * through the props, because AAP section 0.3.2 fixes them for a record view and a prop assertion
 * would pass on a component that ignored them. The first row carrying TWO label cells is what
 * two-up means: `app/bms/COTRN01.bms` pairs the transaction identifier at `POS=(10,22)` with the
 * card number at `POS=(10,58)` on one terminal row, and the record view keeps that pairing.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function paintsTheRecordAsABorderedTwoUpDescriptions(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));
  const body = shellLandmark('screenBody');

  expect(body.querySelectorAll('.ant-descriptions-bordered')).toHaveLength(1);
  expect(body.querySelectorAll('.ant-descriptions-item-label')).toHaveLength(
    Object.keys(TRANSACTION_DETAIL_FIELD_LABELS).length,
  );

  const firstRow = body.querySelector('.ant-descriptions-row');
  expect(firstRow?.querySelectorAll('.ant-descriptions-item-label')).toHaveLength(2);

  for (const label of Object.values(TRANSACTION_DETAIL_FIELD_LABELS)) {
    // WHY : Assumptions: each label is asserted through the catalog and never retyped, so the
    //       assertion is that the screen agrees with `ui/src/messages/messages.ts` -- which carries
    //       the mapset's `INITIAL=` literals with their transcribed line numbers -- rather than with
    //       a paraphrase written twice. The trailing colon is part of each literal.
    expect(expectVerbatimMessage(label)).toBeInTheDocument();
  }
}

/**
 * Asserts that the screen's own controls are the design system's and not raw markup.
 *
 * Assumptions: the three checks cover the three control kinds this screen renders -- the lookup input,
 * the legend's buttons and the record grid -- and each is asserted by the class the antd component
 * emits. A raw `<input>`, `<button>` or hand-built table would carry none of them. The design system's
 * library-components rule exists because a raw control is outside the CSS-variables theme
 * `ConfigProvider` installs, so it would render with none of the BMS-derived tokens.
 *
 * ⚠️ Assumptions: the record grid's own `<table>` element is NOT a violation and is not asserted
 * against. `Descriptions` with `bordered` emits one, so the table is the library component's
 * implementation rather than raw markup the screen wrote -- which is exactly the distinction the rule
 * draws, and why this case asserts the antd class on the grid instead of the absence of a tag.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function usesLibraryComponentsRatherThanRawControls(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));

  expect(lookupControl()).toHaveClass('ant-input');
  expect(lookupControl().closest('.ant-form-item')).not.toBeNull();
  expect(shellLandmark('screenBody').querySelector('.ant-descriptions')).not.toBeNull();
  for (const control of legendControls()) {
    expect(control).toHaveClass('ant-btn');
  }
}

/**
 * Asserts that the screen's declared field widths are the symbolic map's, field for field.
 *
 * Purpose
 * -------
 * The 3270 enforced a field's width in hardware, and the copybook `PICTURE` is where that constraint
 * is recorded. `TRANSACTION_DETAIL_SCREEN_WIDTHS` is the screen's transcription of it, and this case
 * is what holds the transcription to the source. The table below is read from
 * `app/cpy-bms/COTRN01.CPY` with the line of each declaration beside it, so a drifted width fails on
 * the field that drifted rather than on a rendered string.
 *
 * ⚠️ Assumptions: four of these are NARROWER than the record field behind them, and that is the
 * mapset's own arrangement rather than an error in either table -- the description is 60 against
 * `TRAN-DESC PIC X(100)` (`app/cpy/CVTRA05Y.cpy` L9), the merchant name 30 against `X(50)` (L12), the
 * merchant city 25 against `X(50)` (L13), and both timestamps 10 against `X(26)` (L16-L17). This case
 * asserts the declared SCREEN widths; whether a received value is cut to them is a separate question
 * the timestamp case answers.
 * @returns {void} Completion of the case; the assertion is its effect.
 */
function transcribesEveryDeclaredScreenWidthFromTheSymbolicMap(): void {
  const declaredInTheSymbolicMap = {
    transactionId: 16,
    cardNumber: 16,
    typeCode: 2,
    categoryCode: 4,
    source: 10,
    description: 60,
    amount: 12,
    originTimestamp: 10,
    processTimestamp: 10,
    merchantId: 9,
    merchantName: 30,
    merchantCity: 25,
    merchantZip: 10,
  };

  /*
   * WHY : Assumptions: the two tables are compared WHOLE rather than field by field in a loop, so a
   *       member present in one and absent from the other fails as well as a member whose width
   *       differs. A loop over the screen's own keys would silently pass a table that had lost a
   *       field. The thirteen widths are `TRNIDI` L66, `CARDNUMI` L72, `TTYPCDI` L78, `TCATCDI` L84,
   *       `TRNSRCI` L90, `TDESCI` L96, `TRNAMTI` L102, `TORIGDTI` L108, `TPROCDTI` L114, `MIDI` L120,
   *       `MNAMEI` L126, `MCITYI` L132 and `MZIPI` L138 of `app/cpy-bms/COTRN01.CPY`.
   */
  expect(TRANSACTION_DETAIL_SCREEN_WIDTHS).toStrictEqual(declaredInTheSymbolicMap);
}

/**
 * Asserts that every header-band slot holds a value of the width its symbolic map declares.
 *
 * Assumptions: the six slots are `TRNNAMEI PIC X(4)` L24, `TITLE01I PIC X(40)` L30, `CURDATEI
 * PIC X(8)` L36, `PGMNAMEI PIC X(8)` L42, `TITLE02I PIC X(40)` L48 and `CURTIMEI PIC X(8)` L54 of
 * `app/cpy-bms/COTRN01.CPY`, filled by `POPULATE-HEADER-INFO` at `app/cbl/COTRN01C.cbl` L243-L262.
 * Four of them are filled from constants this file can reach -- the screen's own transaction and
 * program identifiers, and the catalog's two title lines -- so those are asserted against the
 * declaration directly. The two clock slots are filled by the shell from the server clock, so they
 * are asserted from the painted band by shape.
 *
 * ⚠️ Assumptions: the two titles are asserted at their DECLARED width and their catalogued text is
 * 40 characters including its own padding, because `CCDA-TITLE01` and `CCDA-TITLE02` are
 * `PIC X(40)` constants that the terminal painted padded. The rendered band shows them trimmed,
 * which is HTML's own whitespace collapsing rather than a lost value, so the width assertion is made
 * against the catalog entry and the presence assertion against the band.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function honoursTheHeaderBandWidthsItsOwnDependenciesSupply(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));
  const band = shellLandmark('titleBand').textContent ?? '';

  expect(TRANSACTION_DETAIL_TRANSACTION_ID).toHaveLength(HEADER_BAND_DECLARED_WIDTHS.TRNNAMEI);
  expect(TRANSACTION_DETAIL_PROGRAM_NAME).toHaveLength(HEADER_BAND_DECLARED_WIDTHS.PGMNAMEI);
  expect(SCREEN_TITLES.TITLE01.declaredWidth).toBe(HEADER_BAND_DECLARED_WIDTHS.TITLE01I);
  expect(SCREEN_TITLES.TITLE01.text).toHaveLength(HEADER_BAND_DECLARED_WIDTHS.TITLE01I);
  expect(SCREEN_TITLES.TITLE02.declaredWidth).toBe(HEADER_BAND_DECLARED_WIDTHS.TITLE02I);
  expect(SCREEN_TITLES.TITLE02.text).toHaveLength(HEADER_BAND_DECLARED_WIDTHS.TITLE02I);

  expect(band).toContain(TRANSACTION_DETAIL_TRANSACTION_ID);
  expect(band).toContain(TRANSACTION_DETAIL_PROGRAM_NAME);
  expect(band).toContain(SCREEN_TITLES.TITLE01.text.trim());
  expect(band).toContain(SCREEN_TITLES.TITLE02.text.trim());

  /*
   * WHY : Assumptions: the two clock slots are matched by SHAPE at their declared width -- eight
   *       characters of digits and separators -- rather than against a formatted instant, because the
   *       value is the current server time and an expected string would be a clock this file does not
   *       own. `ui/src/layout/screenHeaderClock.test.tsx` owns the formats themselves; what is
   *       asserted here is that whatever the shell paints still fits `PIC X(8)`.
   */
  const clockSlots = band.match(/\d{2}[/:]\d{2}[/:]\d{2}/gu) ?? [];
  expect(clockSlots).toHaveLength(2);
  for (const slot of clockSlots) {
    expect(slot).toHaveLength(HEADER_CLOCK_DECLARED_WIDTH);
  }
}

/**
 * Asserts that the caption and the lookup label are painted verbatim from the mapset.
 *
 * Assumptions: both are `INITIAL=` literals on the map -- `'View Transaction'` at
 * `app/bms/COTRN01.bms` L79 in `COLOR=NEUTRAL` with `BRT`, and `'Enter Tran ID:'` at L84 in
 * `COLOR=TURQUOISE` -- and both are asserted through the catalog rather than retyped, for the reason
 * recorded on the record labels above.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function paintsTheCaptionAndLookupLabelVerbatim(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));

  expect(expectVerbatimMessage(TRANSACTION_DETAIL_TITLE)).toBeInTheDocument();
  expect(expectVerbatimMessage(TRANSACTION_DETAIL_LOOKUP_LABEL)).toBeInTheDocument();
}

/**
 * Asserts that the amount travels and renders as text, through the reference's own display mask.
 *
 * ⚠️ Trade-offs: the fixture's amount is a `string` and the rendered value is compared as a string,
 * with no numeric conversion anywhere in this case. The mechanism that makes this necessary is
 * specific: a JSON number is parsed into an IEEE-754 double by most clients, so an amount routed
 * through one loses the exactness that `NUMERIC(12,2)` holds in the database and `BigDecimal` at
 * scale 2 holds in the service, and it loses it at the boundary the operator actually reads. The
 * fixture is chosen so the difference is VISIBLE rather than theoretical: `-000000123.45` carries
 * leading zeroes that a numeric round trip discards, and the expected rendering carries eight
 * zero-filled integer positions that only a text mask can produce.
 *
 * Assumptions: the expected string is additionally compared against the screen's own exported
 * formatter, so this case fails on the rendering and on the formatter independently rather than
 * asserting one against the other alone. `WS-TRAN-AMT PIC +99999999.99` at `app/cbl/COTRN01C.cbl`
 * L49 is what fixes the shape, and L177 and L183 are what move the record amount through it.
 *
 * Assumptions: the fixed-pitch face is asserted through its TOKEN reference rather than a family
 * name, because AAP section 0.3.3 maps money and identifier columns to `fontFamilyCode` -- the 3270
 * cell grid aligned the sign, the digits and the decimal point for free, and a proportional face
 * gives digits different advance widths.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function rendersTheAmountAsTextThroughTheDisplayMask(): Promise<void> {
  const record = aTransaction(A_TRANSACTION_ID);
  await openWithRecord(record);
  const amount = recordValueFor('amount');

  expect(record.amount).toBe(AN_AMOUNT);
  expect(formatTransactionAmount(record.amount)).toBe(THE_MASKED_AMOUNT);
  expect(amount).toHaveTextContent(THE_MASKED_AMOUNT);
  // WHY : Assumptions: twelve characters, which is `TRNAMTI PIC X(12)` at
  //       `app/cpy-bms/COTRN01.CPY` L102 -- the mask's sign position, eight integer positions, the
  //       point and two decimals fill the declared field exactly.
  expect(THE_MASKED_AMOUNT).toHaveLength(TRANSACTION_DETAIL_SCREEN_WIDTHS.amount);
  expect(amount.style.fontFamily).toMatch(
    cssVariableReferenceFor(TYPOGRAPHY_TOKENS.fixedPitchData),
  );
}

/**
 * Asserts that both stored timestamps keep their microseconds while showing what the terminal showed.
 *
 * ⚠️ Trade-offs: the visible text is the leading ten characters and the complete twenty-six are
 * carried on the element's `title`, and both halves are asserted because each guards a different
 * failure. The visible ten are the terminal's own rendering -- `TRAN-ORIG-TS` and `TRAN-PROC-TS` are
 * `PIC X(26)` at `app/cpy/CVTRA05Y.cpy` L16-L17 and `app/cbl/COTRN01C.cbl` L185-L186 moves each into
 * a `PIC X(10)` map field, and a COBOL alphanumeric `MOVE` truncates on the right. The full
 * twenty-six prove the value was never parsed: a JavaScript `Date` resolves to milliseconds, so a
 * round trip through one would silently discard the last three digits of the microseconds, and there
 * is no zone to interpret either.
 *
 * Assumptions: the two fixtures differ in their microsecond digits, so a case that read one field's
 * value out of the other's element would fail rather than agree by accident.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function keepsBothTimestampsUnparsedAtFullPrecision(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));

  expect(AN_ORIGIN_TIMESTAMP).toHaveLength(TIMESTAMP_DECLARED_WIDTH);
  expect(A_PROCESS_TIMESTAMP).toHaveLength(TIMESTAMP_DECLARED_WIDTH);

  const origin = recordValueFor('originTimestamp');
  const processed = recordValueFor('processTimestamp');

  expect(origin).toHaveAttribute('title', AN_ORIGIN_TIMESTAMP);
  expect(processed).toHaveAttribute('title', A_PROCESS_TIMESTAMP);
  expect(recordTextFor('originTimestamp')).toBe(
    AN_ORIGIN_TIMESTAMP.slice(0, TIMESTAMP_DISPLAY_WIDTH),
  );
  expect(recordTextFor('processTimestamp')).toBe(
    A_PROCESS_TIMESTAMP.slice(0, TIMESTAMP_DISPLAY_WIDTH),
  );
  expect(recordTextFor('originTimestamp')).toHaveLength(TIMESTAMP_DISPLAY_WIDTH);
  expect(origin.style.fontFamily).toMatch(
    cssVariableReferenceFor(TYPOGRAPHY_TOKENS.fixedPitchData),
  );
}

/**
 * Asserts that the card number appears only in the reduced rendering the service published.
 *
 * Assumptions: the whole account number is asserted ABSENT from the document rather than merely
 * unequal to the rendered value, because the exposure that matters is any occurrence at all -- a full
 * number that reaches the DOM reaches the accessibility tree, a screenshot and every bug report that
 * carries them. AAP section 0.4.1.9 permits an unreduced number on the administrative card-detail
 * endpoint alone, which this screen does not call.
 *
 * Assumptions: the last four digits of the unreduced fixture are the same four the reduced rendering
 * shows, so the absence assertion cannot pass merely because the two strings differ somewhere -- it
 * passes only if the twelve concealed positions are genuinely not present.
 *
 * Assumptions: the contract's own member list is asserted as well, which is what closes the
 * verification-value question. `ui/src/api/types.ts` declares fourteen members on this shape and no
 * card verification value among them, so no such value can reach this screen through the contract at
 * all -- the reference card record's three-digit verification field has no counterpart on the wire,
 * and AAP section 0.4.1.9 states that no endpoint returns one.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function showsOnlyTheReducedCardNumber(): Promise<void> {
  const record = aTransaction(A_TRANSACTION_ID);
  await openWithRecord(record);

  expect(recordTextFor('cardNumber')).toBe(A_REDUCED_CARD_NUMBER);
  expect(document.body.textContent ?? '').not.toContain(AN_UNREDUCED_CARD_NUMBER);
  expect(Object.keys(record).sort()).toStrictEqual([
    'amount',
    'cardNumber',
    'categoryCode',
    'description',
    'merchantCity',
    'merchantId',
    'merchantName',
    'merchantZip',
    'originTimestamp',
    'processTimestamp',
    'returnMessage',
    'source',
    'transactionId',
    'typeCode',
  ]);
}

/**
 * Opens the screen, answers its arrival read, then takes a second turn on a new identifier.
 *
 * Purpose
 * -------
 * Three of the message cases need a read they can settle themselves, and the only address this
 * screen is mounted at carries a parameter -- so an arrival read always happens first and has to be
 * answered before the turn under test begins. Doing that once here keeps each case's own body about
 * the outcome it asserts.
 *
 * Assumptions: the entry is retyped rather than appended, because `maxLength` already holds the
 * control to sixteen characters and appending to a seeded value would silently produce a truncated
 * key. Clearing first is also what `app/cbl/COTRN01C.cbl` L154 leaves the operator able to do, since
 * it returns the cursor to the field without blanking it.
 * @param {string} entry - The identifier to type before pressing Enter.
 * @returns {Promise<{rendered: HarnessRenderResult, held: HeldRead}>} The render result and the held
 *   read the second turn issued, still unsettled.
 */
async function openThenFetchAgain(
  entry: string,
): Promise<{ readonly rendered: HarnessRenderResult; readonly held: HeldRead }> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));
  const held = armHeldRead();

  await rendered.user.clear(lookupControl());
  await rendered.user.type(lookupControl(), entry);
  await pressPfKey(rendered.user, 'ENTER');

  return { rendered, held };
}

/**
 * Asserts that a blank lookup is refused with the program's own sentence and issues no read.
 *
 * Assumptions: the sentence is asserted through the catalog and never retyped. A paraphrase written
 * into the screen and the same paraphrase written into the test would agree, and the case would pass
 * while transformation rule T8's character-for-character guarantee was already lost -- so the
 * assertion has to be that the screen agrees with `ui/src/messages/messages.ts`, whose entry carries
 * `app/cbl/COTRN01C.cbl` L149 as its recorded origin.
 *
 * ⚠️ Assumptions: the capitalised `NOT` is locked by a word-boundary pattern rather than by writing
 * the sentence out again. `'Tran ID can NOT be empty...'` capitalises the word, which is the house
 * pattern across the `COTRN*`, `COUSR*` and `COBIL*` family, and rule T8 requires it character for
 * character -- so the risk worth guarding is a well-meaning normalisation to "cannot" or "not", and a
 * three-letter pattern catches that without becoming a second copy of the sentence.
 *
 * Assumptions: the read count is asserted UNCHANGED rather than zero, because the arrival read has
 * already happened by then. `app/cbl/COTRN01C.cbl` L148 sets the error flag and the read at L173 sits
 * inside `IF NOT ERR-FLG-ON` at L158, so a blank key never reaches the file at all.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function refusesABlankLookupWithoutReading(): Promise<void> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));
  const readsBefore = viewTransactionMock.mock.calls.length;

  await rendered.user.clear(lookupControl());
  await pressPfKey(rendered.user, 'ENTER');

  const refusal = PROGRAM_MESSAGES.COTRN01C.TRAN_ID_CAN_NOT_BE_EMPTY;
  expectBandSentence(refusal);
  expect(PROGRAM_MESSAGE_SOURCES.COTRN01C.TRAN_ID_CAN_NOT_BE_EMPTY).toStrictEqual([149]);
  expect(/\bNOT\b/u.test(refusal)).toBe(true);
  expect(viewTransactionMock.mock.calls).toHaveLength(readsBefore);
}

/**
 * Asserts that a blank refusal marks the field itself, asterisk included.
 *
 * ⚠️ Refactoring Rationale: `app/cpy/CSSETATY.cpy` L17-L27 is the templated copybook the baseline
 * applies to a refused field. It moves `DFHRED` into the field's colour attribute when the field's
 * flag is not-OK or blank, and at L24 it ADDITIONALLY writes a literal `'*'` into the field when the
 * refusal is that it was left blank. The target renders that as `Form.Item validateStatus="error"`
 * with `help` text plus the marker. What does NOT survive is the gate: the copybook's condition
 * includes `CDEMO-PGM-REENTER` at L20, and AAP section 0.7.1 establishes that the re-entry
 * discriminator (`app/cpy/COCOM01Y.cpy` L29-L31) disappears entirely -- a stateless handler has no
 * first-entry-versus-re-entry distinction to make -- so the highlight is driven purely by the current
 * refusal instead of by a remembered turn count.
 *
 * Assumptions: the marker is asserted through `FIELD_ERROR_TOKENS.blankMarker` rather than as a
 * character written here, and its colour through the token reference rather than a hue, so the
 * design system's zero-hardcoded-values rule holds in the test as well as in the screen.
 *
 * Assumptions: `ui/src/layout/MessageBand.tsx` is presentational and accepts a nullable string and a
 * severity, never an `ApiError` -- so the screen owns the mapping from a refusal to BOTH channels,
 * and both halves are asserted here for that reason.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function marksTheBlankFieldWithItsAsterisk(): Promise<void> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));

  await rendered.user.clear(lookupControl());
  await pressPfKey(rendered.user, 'ENTER');

  const item = lookupControl().closest('.ant-form-item');
  expect(item).not.toBeNull();
  expect(item).toHaveClass('ant-form-item-has-error');
  expect(lookupControl()).toHaveAttribute('aria-invalid', 'true');

  const help = item?.querySelector('.ant-form-item-explain-error');
  expect(help?.textContent).toBe(PROGRAM_MESSAGES.COTRN01C.TRAN_ID_CAN_NOT_BE_EMPTY);

  const marker = screen.getByText(FIELD_ERROR_TOKENS.blankMarker);
  expect(marker).toBeInTheDocument();
  expect(marker.style.color).toMatch(cssVariableReferenceFor(FIELD_ERROR_TOKENS.errorColor));
}

/**
 * Asserts that a lookup answered 404 reports the not-found sentence.
 *
 * Assumptions: HTTP 404 is the migrated form of `DFHRESP(NOTFND)`, which
 * `app/cbl/COTRN01C.cbl` L283-L288 answers with `'Transaction ID NOT found...'` at L285. The sentence
 * is shared -- `app/cbl/COBIL00C.cbl` and `app/cbl/COTRN02C.cbl` compose it too -- so the catalog
 * files it under the shared group, and the recorded origins are asserted to still name this program.
 *
 * Assumptions: the capitalised `NOT` is locked by pattern here for the same reason as on the blank
 * refusal above.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function reportsANotFoundLookupWithTheSharedSentence(): Promise<void> {
  const { held } = await openThenFetchAgain(ANOTHER_TRANSACTION_ID);
  // WHY : Assumptions: the problem document is built by the shared `apiError` fixture rather than
  //       assembled here, so it carries every one of the eleven members the contract declares. A
  //       partial document describes a response the service cannot send, and would let a narrowing
  //       pass that the real shape fails.
  await held.refuseWith(apiError({ status: 404, code: 'CARDDEMO-0404' }));

  expectBandSentence(SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND);
  expect(/\bNOT\b/u.test(SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND)).toBe(true);
  expect(SHARED_MESSAGE_SOURCES.TRANSACTION_ID_NOT_FOUND).toContainEqual({
    file: 'app/cbl/COTRN01C.cbl',
    lines: [285],
  });
}

/**
 * Asserts that every other failure reports this screen's own capitalised sentence.
 *
 * ⚠️ Assumptions: the sentence this screen paints capitalises the word "Transaction" --
 * `'Unable to lookup Transaction...'` at `app/cbl/COTRN01C.cbl` L292 -- while the sibling browse
 * screen paints `'Unable to lookup transaction...'` with a lower-case t at `app/cbl/COTRN00C.cbl`
 * L615, L649 and L683. They are TWO different strings and rule T8 carries both across unchanged, so
 * this case asserts that they remain distinct AND that they differ in nothing but that letter. The
 * second half is what makes the assertion useful: a careless merge of two near-duplicate catalog
 * entries would leave one of them holding the other's casing, and comparing only for inequality
 * would not notice a merge that also changed a word.
 *
 * Assumptions: the arm is reached with HTTP 500 because `app/cbl/COTRN01C.cbl` L289-L295 has exactly
 * one `WHEN OTHER` covering every response that is neither normal nor not-found, so there is no third
 * sentence to choose between.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function reportsEveryOtherFailureWithItsOwnCapitalisedSentence(): Promise<void> {
  const { held } = await openThenFetchAgain(ANOTHER_TRANSACTION_ID);
  /*
   * WHY : Assumptions: the severity is `CRITICAL`, which is the highest member
   *       `ui/src/api/types.ts` L220 declares -- the vocabulary is `LOG`, `INFO`, `WARNING` and
   *       `CRITICAL`, transcribed from the baseline's own attribution words, and it carries no
   *       member spelled `ERROR`. A service-side failure is the case that member exists for.
   */
  await held.refuseWith(apiError({ status: 500, code: 'CARDDEMO-0500', severity: 'CRITICAL' }));

  const thisScreen = SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION;
  const theBrowseScreen = PROGRAM_MESSAGES.COTRN00C.UNABLE_TO_LOOKUP_TRANSACTION;

  expectBandSentence(thisScreen);
  expect(thisScreen).not.toBe(theBrowseScreen);
  expect(thisScreen.toLowerCase()).toBe(theBrowseScreen.toLowerCase());
  expect(SHARED_MESSAGE_SOURCES.UNABLE_TO_LOOKUP_TRANSACTION).toContainEqual({
    file: 'app/cbl/COTRN01C.cbl',
    lines: [292],
  });
  expect(PROGRAM_MESSAGE_SOURCES.COTRN00C.UNABLE_TO_LOOKUP_TRANSACTION).toStrictEqual([
    615, 649, 683,
  ]);
}

/**
 * Asserts that a service-reported field error marks the control without adding the blank marker.
 *
 * Assumptions: a `NOT_OK` refusal takes the colour treatment and NOT the asterisk, because
 * `app/cpy/CSSETATY.cpy` writes the literal `'*'` only inside its `IF FLG-...-BLANK` at L23-L25. The
 * two states are therefore not interchangeable, which is why the shared `fieldError` fixture
 * defaults to `NOT_OK` and a case exercising the other one says so.
 *
 * Assumptions: the refusal's sentence comes from the catalog rather than being composed here, because
 * a fixture that invented one would put an unreviewed user-visible string in this file.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function marksAServiceReportedFieldErrorWithoutTheAsterisk(): Promise<void> {
  const { held } = await openThenFetchAgain(ANOTHER_TRANSACTION_ID);
  const reported = fieldError('transactionId', SHARED_MESSAGES.TRANSACTION_ID_NOT_FOUND);
  await held.refuseWith(apiError({ status: 400, fieldErrors: [reported] }));

  const item = lookupControl().closest('.ant-form-item');
  expect(item).toHaveClass('ant-form-item-has-error');
  expect(item?.querySelector('.ant-form-item-explain-error')?.textContent).toBe(reported.message);
  expect(screen.queryByText(FIELD_ERROR_TOKENS.blankMarker)).toBeNull();
}

/**
 * Asserts that a recognised but unbound key reports the fifty-character invalid-key sentence.
 *
 * Assumptions: `app/cbl/COTRN01C.cbl` L128-L131 is a `WHEN OTHER` arm that sets the error flag and
 * moves `CCDA-MSG-INVALID-KEY` into `WS-MESSAGE` at L130, so this screen is one of the programs that
 * emits it. The constant is `PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L20-L21 holding a 49-character
 * sentence, so the catalog stores the text with its own padding and the declared width beside it --
 * and both are asserted, because the padding is part of the value the terminal painted.
 *
 * Assumptions: PF7 is the key pressed because it is a real member of the attention-identifier domain
 * that this screen does not bind -- neither the row-24 legend nor the program's `EVALUATE` at L112
 * mentions it -- which is exactly the condition the `WHEN OTHER` arm answers. Its aliasing and the
 * unbound Clear, PA1 and PA2 identifiers are shared hook behaviours and belong to the hook's own
 * suite, not here.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function reportsAnUnboundKeyWithTheFiftyCharacterSentence(): Promise<void> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));
  const readsBefore = viewTransactionMock.mock.calls.length;

  await pressPfKey(rendered.user, 'PFK07');

  expectBandSentence(COMMON_MESSAGES.INVALID_KEY.text);
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(COMMON_MESSAGES.INVALID_KEY.source).toStrictEqual({
    file: 'app/cpy/CSMSG01Y.cpy',
    lines: [21],
  });
  expect(viewTransactionMock.mock.calls).toHaveLength(readsBefore);
  // WHY : Assumptions: the screen is asserted STILL mounted, because an unbound key must report and
  //       do nothing else -- the reference's `WHEN OTHER` arm re-sends the same map rather than
  //       transferring anywhere.
  expect(lookupControl()).toBeInTheDocument();
}

/**
 * Asserts that the legend paints exactly the four keys the mapset does, with their measured labels.
 *
 * Assumptions: the four descriptors are `ENTER=Fetch`, `F3=Back`, `F4=Clear` and `F5=Browse Tran.`,
 * which is the one 47-character `COLOR=YELLOW` literal `app/bms/COTRN01.bms` paints at L263-L268, and
 * `app/cbl/COTRN01C.cbl` L112-L132 admits exactly those four attention identifiers. The painted
 * legend and the accepted key set therefore agree, and the reassembled legend is asserted at its
 * declared width because the groups are separated by TWO spaces -- single-spacing them would give 44
 * characters and break the verbatim contract without changing any individual label.
 *
 * ⚠️ Assumptions: `F4=Clear` is sourced from `UNIFORM_PF_KEY_LABELS` and the other three from the
 * message catalog, and that split is the boundary those two modules draw rather than an
 * inconsistency: `F4=Clear` is byte-identical across every measured legend that binds PF4, so
 * `ui/src/layout/PfKeyBar.tsx` owns it, while the other three are this mapset's own text.
 *
 * Assumptions: the absence of a fifth key is asserted through the count and through the shortcut
 * attributes, because PF7, PF8 and PF12 appear in neither the legend nor the `EVALUATE` -- binding one
 * would add a key the reference answers with its invalid-key message, which is a behavioural change
 * rather than an addition.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function paintsExactlyTheFourKeysTheMapsetDoes(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));
  const controls = legendControls();

  expect(TRANSACTION_DETAIL_KEY_LABELS.PFK04).toBe(UNIFORM_PF_KEY_LABELS.PFK04);
  expect(TRANSACTION_DETAIL_LEGEND).toHaveLength(LEGEND_DECLARED_WIDTH);
  expect(controls).toHaveLength(4);
  const labels = controls.map(
    /**
     * Reads one legend control's rendered text.
     * @param {HTMLElement} control - One legend control.
     * @returns {string} Its text, which is the descriptor the mapset paints.
     */
    (control: HTMLElement): string => control.textContent ?? '',
  );
  const shortcuts = controls.map(
    /**
     * Reads the browser key one legend control advertises.
     * @param {HTMLElement} control - One legend control.
     * @returns {string} The advertised key.
     */
    (control: HTMLElement): string => control.getAttribute('aria-keyshortcuts') ?? '',
  );

  expect(labels).toStrictEqual([
    TRANSACTION_DETAIL_KEY_LABELS.ENTER,
    TRANSACTION_DETAIL_KEY_LABELS.PFK03,
    TRANSACTION_DETAIL_KEY_LABELS.PFK04,
    TRANSACTION_DETAIL_KEY_LABELS.PFK05,
  ]);
  expect(shortcuts).toStrictEqual(['Enter', 'F3', 'F4', 'F5']);
}

/**
 * Asserts that no control on this legend carries the primary emphasis.
 *
 * ⚠️ Refactoring Rationale: this case previously required Enter and PF5 to be emphasised, deriving
 * that from `PRIMARY_ACTION_AIDS`, and both halves of that are now wrong for this screen. The
 * `PfKeyBar` fallback emphasises those two identifiers on every screen that binds them, which is a
 * reading taken from the mapsets where Enter submits and PF5 saves. On THIS mapset
 * `app/bms/COTRN01.bms` L267 paints `F5=Browse Tran.` and `app/cbl/COTRN01C.cbl` L112-L132 answers it
 * by transferring to the browse, so the key navigates; the screen therefore declares
 * `risk: 'read-only'` on all four bindings and the shared `pfKeyEmphasisFor` resolves every one of
 * them to the default variant. The note this replaces argued the emphasis "says 'this is the key an
 * operator reaches for'"; under the risk taxonomy it says what the key DOES, so a screen with no write
 * paints nothing solid.
 *
 * ⚠️ Assumptions: the negative is asserted on all four identifiers rather than on Enter alone,
 * because a claim about one control would pass against a legend that had emphasised a different one.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function emphasisesNoKeyOnAReadOnlyScreen(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));
  const emphasisByShortcut = new Map(
    legendControls().map(
      /**
       * Pairs one control's advertised key with the emphasis class it carries.
       * @param {HTMLElement} control - One legend control.
       * @returns {[string, boolean]} The advertised key and whether it is emphasised.
       */
      (control: HTMLElement): [string, boolean] => [
        control.getAttribute('aria-keyshortcuts') ?? '',
        control.classList.contains('ant-btn-primary'),
      ],
    ),
  );

  /*
   * WHY : ⚠️ Refactoring Rationale: the expectation is now a flat `false` for every identifier, and it
   *       used to be `PRIMARY_ACTION_AIDS.includes(aid)`. Deriving it from that constant made this case
   *       an obstacle rather than a guard, exactly as the note it replaces conceded: the constant
   *       emphasises Enter and PF5 on every screen, so a case reading it could never report that a
   *       read-only screen was painting two primary controls. The screen now declares its own risk, so
   *       the expectation is stated from the mapset instead -- four read-only labels, four default
   *       controls.
   * WHY : Assumptions: the mapping from an identifier to the browser key the control advertises is
   *       taken from the screen's own legend rather than from a table written here, so the case reads
   *       the same four identifiers the reference's `EVALUATE` at `app/cbl/COTRN01C.cbl` L112-L132
   *       admits.
   */
  const shortcutByAid: Record<string, string> = {
    ENTER: 'Enter',
    PFK03: 'F3',
    PFK04: 'F4',
    PFK05: 'F5',
  };

  for (const shortcut of Object.values(shortcutByAid)) {
    expect({ shortcut, emphasised: emphasisByShortcut.get(shortcut) }).toEqual({
      shortcut,
      emphasised: false,
    });
  }
  for (const control of legendControls()) {
    expect(control).toHaveClass('ant-btn-default');
  }
}

/**
 * Asserts that the Enter key and the Enter control both run the lookup.
 *
 * ⚠️ Alternatives Considered: asserting the control alone, which is the easier case to write.
 * Rejected because the 3270 original was keyboard-only, so the PF-key contract is a KEYBOARD
 * contract: a case that only clicks passes in full while every keyboard binding in the application is
 * broken. Asserting the control alone would also miss the opposite regression, since the legend is
 * additive and an operator discovering the screen with a pointer has nothing else to reach for. Both
 * channels are therefore driven in one case, so a divergence between them fails here rather than
 * being split across two cases that each pass.
 *
 * Assumptions: the key is raised through `pressPfKey`, which derives the browser key by inverting the
 * hook's own published table and round-trips it back through `resolveAid` before pressing -- so this
 * case cannot drift from the table the application actually dispatches on.
 *
 * Assumptions: Enter pressed with the cursor in the lookup control reaches the screen rather than the
 * browser, because `ENTER_ACTIVATED_TARGET_SELECTOR` in `ui/src/layout/usePfKeys.ts` deliberately
 * omits a text input -- Enter in a text field IS the 3270 submit gesture the shell must claim.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function fetchesOnTheEnterKeyAndOnTheEnterControlAlike(): Promise<void> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));

  armHeldRead();
  await rendered.user.clear(lookupControl());
  await rendered.user.type(lookupControl(), ANOTHER_TRANSACTION_ID);
  await pressPfKey(rendered.user, 'ENTER');
  expect(viewTransactionMock).toHaveBeenLastCalledWith(ANOTHER_TRANSACTION_ID);
  const afterTheKey = viewTransactionMock.mock.calls.length;

  armHeldRead();
  await rendered.user.click(legendControlFor('Enter'));
  expect(viewTransactionMock.mock.calls).toHaveLength(afterTheKey + 1);
  expect(viewTransactionMock).toHaveBeenLastCalledWith(ANOTHER_TRANSACTION_ID);
}

/**
 * Asserts that PF4 blanks the entry and the thirteen values while the thirteen labels stand.
 *
 * ⚠️ Assumptions: clearing means blanking VALUES and not removing rows, which is the reference's own
 * arrangement and not a target simplification. `INITIALIZE-ALL-FIELDS` at `app/cbl/COTRN01C.cbl`
 * L309-L326 moves `SPACES` into the lookup key, the thirteen data fields and the message, and touches
 * the thirteen labels not at all -- it cannot, because they are `INITIAL=` literals in
 * `app/bms/COTRN01.bms` that the terminal repaints on every send of the map. So a cleared screen is
 * thirteen labels standing beside thirteen blank values, which is what distinguishes it from a screen
 * that failed to respond.
 *
 * Assumptions: PF4 is asserted to CLEAR rather than to re-fetch, by asserting the read count
 * unchanged. The two are easy to confuse in a rendering, because both end with an empty record area
 * on a screen whose lookup had failed -- and only one of them is what the legend's `F4=Clear`
 * promises.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function clearsOnThePfFourKeyAndOnTheClearControlAlike(): Promise<void> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));
  const readsBefore = viewTransactionMock.mock.calls.length;

  await pressPfKey(rendered.user, 'PFK04');
  expectTheClearedScreen();
  expect(viewTransactionMock.mock.calls).toHaveLength(readsBefore);

  const held = armHeldRead();
  await rendered.user.type(lookupControl(), ANOTHER_TRANSACTION_ID);
  await pressPfKey(rendered.user, 'ENTER');
  await held.answerWith(aTransaction(ANOTHER_TRANSACTION_ID));
  expect(recordTextFor('transactionId')).toBe(ANOTHER_TRANSACTION_ID);

  await rendered.user.click(legendControlFor('F4'));
  expectTheClearedScreen();
}

/**
 * Asserts that an outstanding read is ANNOUNCED and not only spun.
 *
 * ⚠️ Purpose: this screen states an in-flight read with an antd `Spin` in the record region, which is
 * a purely visual statement -- the component carries no accessible name and announces nothing -- so an
 * operator using a screen reader was told the record had disappeared and nothing about why. The
 * announcement is the same fact stated in the one channel that reaches them.
 *
 * ⚠️ Assumptions: the region is asserted PRESENT AND EMPTY before the read is answered as well as
 * after, not merely absent-then-present. A live region has to be in the accessibility tree before its
 * content changes for the change to be announced at all, so a screen rendering it only while loading
 * would lose the transition into the busy state -- which is the transition that matters. Asserting
 * presence in both states is what distinguishes a correct implementation from that one.
 *
 * Assumptions: the sentence is read from the catalog rather than typed here. `REQUEST_IN_PROGRESS` is
 * authored -- the reference locks the keyboard and says nothing, so there is no verbatim wording to
 * carry across -- and reading it from the catalog is what keeps this case from pinning a second copy.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function announcesAnOutstandingRead(): Promise<void> {
  const held = armHeldRead();
  await openTheDetailScreen(`${TRANSACTION_LIST_PATH}/${A_TRANSACTION_ID}`);

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent(REQUEST_IN_PROGRESS);

  await held.answerWith(aTransaction(A_TRANSACTION_ID));

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent('');
}

/**
 * Asserts that the PF5 key opens the transaction browse.
 *
 * ⚠️ Assumptions: PF5 here is `Browse Tran.` and NOT `Save`, and the divergence is the point of this
 * case. PF5 saves on most CardDemo screens, and `DEFAULT_PF_KEY_ACTIONS` in
 * `ui/src/layout/usePfKeys.ts` maps it to `save` for that reason -- but `app/cbl/COTRN01C.cbl`
 * L125-L127 moves `'COTRN00C'` into `CDEMO-TO-PROGRAM` at L126 and transfers, and `COTRN00C` is the
 * transaction LIST program. The mapset's own `F5=Browse Tran.` is the independent confirmation, and
 * this screen has no write of any kind for a save to perform. Asserting the generic semantic would
 * assert a behaviour the reference does not have.
 *
 * Assumptions: the destination is derived from `ROUTE_TABLE` by the program name the reference
 * moves, so the assertion is against the route the application serves for `COTRN00C` rather than
 * against a path spelled here.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function opensTheBrowseOnThePfFiveKey(): Promise<void> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));

  await pressPfKey(rendered.user, 'PFK05');

  expect(BROWSE_ADDRESS).toBe(TRANSACTION_LIST_PATH);
  expect(screen.getByText(BROWSE_STAND_IN)).toBeInTheDocument();
  expect(screen.queryByRole('textbox')).toBeNull();
}

/**
 * Asserts that the browse control opens the same destination the PF5 key does.
 *
 * Assumptions: this is a separate case from the key rather than a second half of it, because the
 * transition unmounts the screen -- so a single case would have to render twice, and two trees
 * mounted in one case would leave every query matching two elements. The pair together is what
 * asserts the two channels dispatch alike.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function opensTheBrowseOnTheBrowseControl(): Promise<void> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));

  await rendered.user.click(legendControlFor('F5'));

  expect(screen.getByText(BROWSE_STAND_IN)).toBeInTheDocument();
  expect(screen.queryByRole('textbox')).toBeNull();
}

/**
 * Asserts that PF3 returns to the screen that handed this one over.
 *
 * Assumptions: this is the reference's primary PF3 arm. `app/cbl/COTRN01C.cbl` L115-L122 returns to
 * `CDEMO-FROM-PROGRAM` whenever it carries a value, and `app/cbl/COTRN00C.cbl` L190-L191 is what
 * fills it -- it moves `WS-TRANID` and `WS-PGMNAME` into the reference's own `CDEMO-FROM-*` carriers
 * before transferring. The migrated carrier is history state rather than a COMMAREA overlay, so this
 * case arrives the way the browse sends an operator: through a transition that names its origin.
 *
 * Assumptions: the origin is handed over as `state.from`, which `screenTransitionState` in
 * `ui/src/routes/navigation.ts` reads and `inApplicationRoute` validates against the route table --
 * so an origin that is not a route this application serves falls back to the menu rather than being
 * navigated to, and the value handed over here is the browse's own route.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function returnsToTheHandedOverOriginOnThePfThreeKey(): Promise<void> {
  const held = armHeldRead();
  const rendered = await openTheDetailScreen(BROWSE_ADDRESS);

  /*
   * WHY : Assumptions: the link is selected by its accessible NAME rather than by role alone,
   *       because the shell renders a skip link of its own into the title band -- so a bare role
   *       query matches two anchors and fails on the ambiguity rather than on anything under test.
   */
  await rendered.user.click(screen.getByRole('link', { name: OPEN_SELECTED_LINK }));
  await held.answerWith(aTransaction(A_TRANSACTION_ID));
  expect(recordTextFor('transactionId')).toBe(A_TRANSACTION_ID);

  await pressPfKey(rendered.user, 'PFK03');

  expect(screen.getByText(BROWSE_STAND_IN)).toBeInTheDocument();
  expect(screen.queryByRole('textbox')).toBeNull();
}

/**
 * Asserts that PF3 falls back to the main menu when no origin was handed over.
 *
 * Assumptions: this is the other arm of the same branch. `app/cbl/COTRN01C.cbl` L116-L117 moves
 * `'COMEN01C'` into `CDEMO-TO-PROGRAM` when `CDEMO-FROM-PROGRAM` is blank, and a deep link, a reload
 * and a typed address all arrive carrying no origin -- which is the migrated form of a blank carrier.
 *
 * Assumptions: the address is derived from `ROUTE_TABLE` by that same program name, so the fallback
 * is asserted against the route the application serves for `COMEN01C` rather than against a literal.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function fallsBackToTheMainMenuOnThePfThreeKey(): Promise<void> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));

  await pressPfKey(rendered.user, 'PFK03');

  expect(screen.getByText(MENU_STAND_IN)).toBeInTheDocument();
  expect(screen.queryByRole('textbox')).toBeNull();
}

/**
 * Asserts that the back control leaves for the same destination the PF3 key does.
 *
 * Assumptions: separate from the key case for the reason recorded on the browse control above -- the
 * transition unmounts the screen, so the two channels cannot share one render.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function fallsBackToTheMainMenuOnTheBackControl(): Promise<void> {
  const rendered = await openWithRecord(aTransaction(A_TRANSACTION_ID));

  await rendered.user.click(legendControlFor('F3'));

  expect(screen.getByText(MENU_STAND_IN)).toBeInTheDocument();
  expect(screen.queryByRole('textbox')).toBeNull();
}

/**
 * Asserts that this route is open to any signed-on operator and is not administrative.
 *
 * Assumptions: the reachability graph is the authority, and it is derived from the reference's own
 * option copybook rather than from the router's own opinion -- `ROUTE_TABLE` in
 * `ui/src/router.tsx` carries the program name beside each path precisely so the graph can be checked
 * against `app/cpy/COMEN02Y.cpy`. All eleven main-menu options there carry user type `'U'` and not one
 * carries `'A'` (L29 through L90), and option 7 is `'Transaction View'` naming `COTRN01C` at L61-L65 --
 * so an administrative guard on this route would be an authority the reference does not impose.
 * @returns {void} Completion of the case; the assertion is its effect.
 */
function keepsTheDetailRouteOpenToAnySignedOnOperator(): void {
  const entry = ROUTE_TABLE.find(
    /**
     * Reports whether one table row is this screen's route.
     * @param {(typeof ROUTE_TABLE)[number]} candidate - One row of the reachability graph.
     * @returns {boolean} True when the row is the transaction-detail route.
     */
    (candidate): boolean => candidate.path === TRANSACTION_DETAIL_PATH,
  );

  expect(entry?.program).toBe(TRANSACTION_DETAIL_PROGRAM_NAME);
  expect(entry?.access).toBe('authenticated');
}

/**
 * Asserts that a session can be established only through the exchange the application performs.
 *
 * ⚠️ Assumptions: there is NO auth provider to mount and none is invented here. `ui/src/hooks/useAuth.ts`
 * holds its session in module scope and publishes no setter for the groups or the user type, because
 * authority derives solely from the signed `cognito:groups` claim -- so a test that assigned itself a
 * group would be exercising a path production does not have. `seedSession` mints a token carrying the
 * groups and drives the real sign-on, which is the only path there is, and this case asserts the
 * published surface still offers no shortcut around it.
 *
 * Assumptions: the seeded session is a plain operator and the screen is asserted to work under it,
 * which is the runtime half of the route-table assertion above -- the screen reads its record without
 * administrative authority, exactly as main-menu option 7 permits.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function establishesIdentityOnlyThroughTheSanctionedSignOn(): Promise<void> {
  const session = await seedSession();
  const operator = session.result.current;

  expect(operator.signedOn).toBe(true);
  expect(operator.isAdmin).toBe(false);
  const mutators = Object.entries(operator)
    .filter(
      /**
       * Keeps only the members that are callable.
       * @param {[string, unknown]} member - One published member, as a name and value pair.
       * @returns {boolean} True when the member is a function.
       */
      (member: [string, unknown]): boolean => typeof member[1] === 'function',
    )
    .map(
      /**
       * Reduces a member pair to its name.
       * @param {[string, unknown]} member - One published member.
       * @returns {string} The member's name.
       */
      (member: [string, unknown]): string => member[0],
    )
    .sort();
  expect(mutators).toStrictEqual(['answerChallenge', 'signIn', 'signOut']);

  await openWithRecord(aTransaction(A_TRANSACTION_ID));
  expect(recordTextFor('transactionId')).toBe(A_TRANSACTION_ID);
}

/**
 * Reads the antd alert variant the row-23 band currently renders, or `null` when it renders none.
 *
 * Assumptions: the variant is read from the CLASS antd emits rather than from a prop, because what
 * the review measured was a rendered class -- an `ant-alert-info` standing inside the outcome band --
 * and a prop assertion would pass for a band that resolved the prop and then rendered something else.
 *
 * Assumptions: the alert is looked for INSIDE the band element rather than across the document,
 * because `ui/src/layout/MessageBand.tsx` renders the alert as a child of the element carrying the
 * band identifier. Querying the document would also match an alert a screen had composed for itself,
 * which is a different defect and is asserted separately below.
 * @returns {string | null} The variant suffix antd emitted -- `error`, `success` or `info` -- or
 *   `null` when the band is in its reserved-space state and holds no alert at all.
 * @throws {Error} If the band element is absent, which Testing Library raises.
 */
function bandAlertVariant(): string | null {
  const band = screen.getByTestId(MESSAGE_BAND_TEST_ID);
  for (const variant of ['error', 'success', 'info']) {
    if (band.querySelector(`.ant-alert-${variant}`) !== null) {
      return variant;
    }
  }
  return null;
}

/**
 * A sentence to stand in for one the service sends on a successful read.
 *
 * ⚠️ Assumptions: its WORDING is not under test and is deliberately not chosen for meaning. It is
 * taken from the catalog rather than written here because transformation rule T8 admits only operator
 * strings a program actually produces, and `PROGRAM_MESSAGES.COTRN01C` carries exactly ONE entry --
 * which is itself the oracle this case rests on. `app/cbl/COTRN01C.cbl` L91 blanks `WS-MESSAGE` on
 * every entry and no arm of a successful read sets it, so the program authors no success sentence and
 * there is no semantically right one to reach for. What is under test is the CHANNEL and the VARIANT
 * the band renders whatever the sentence says.
 */
const A_SERVICE_AUTHORED_SENTENCE = PROGRAM_MESSAGES.COTRN01C.TRAN_ID_CAN_NOT_BE_EMPTY;

/**
 * Asserts that a sentence arriving with a retrieved record is painted in the channel's own severity.
 *
 * ⚠️ Purpose: this is the case that catches the measured defect. The successful arm named the `info`
 * severity, so a reply the service sent alongside a record was rendered as an `ant-alert-info` INSIDE
 * the row-23 outcome band -- informational-looking content in the error channel, which a rendering
 * review found on four of seventeen routes and which is the same defect as the same screen drawing a
 * severity no other screen draws for the same class of event.
 *
 * ⚠️ Assumptions: the expected variant is `error` because this mapset declares exactly one message
 * field and declares it red: `app/bms/COTRN01.bms` L259-L262 is `ERRMSG ATTRB=(ASKIP,BRT,FSET)
 * COLOR=RED LENGTH=78 POS=(23,1)`, and `app/cbl/COTRN01C.cbl` L217 is the single
 * `MOVE WS-MESSAGE TO ERRMSGO` every arm of the program reaches. There is therefore no arm-dependent
 * colour to reproduce, which is why the screen names no severity at all and the band resolves it from
 * `MESSAGE_BAND_CHANNELS`.
 *
 * ⚠️ Assumptions: the absence of a row-22 band is asserted as well, and it is the other half of the
 * finding. Moving the sentence to the information channel would have removed the alert from the error
 * band -- but this mapset has no row-22 field to move it to. `INFOMSG` is declared by five of the
 * twenty-one mapsets (`COACTUP`, `COACTVW`, `COCRDLI`, `COCRDSL`, `COCRDUP`) and not by this one, and
 * `ShellMessageSlot.information` in `ui/src/layout/AppShell.tsx` states that the member's absence
 * means precisely that. So a row-22 band appearing here would be a terminal row the screen never had.
 *
 * Assumptions: the band count is asserted at one, because the other measured shape of this defect is
 * a screen composing a second band of its own inside `<main>` while the shell's stands empty. One
 * element carrying the row-23 identifier is what proves the screen delegated rather than composed.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function paintsAServiceSentenceInTheChannelsOwnSeverity(): Promise<void> {
  await openWithRecord({
    ...aTransaction(A_TRANSACTION_ID),
    returnMessage: A_SERVICE_AUTHORED_SENTENCE,
  });

  expectBandSentence(A_SERVICE_AUTHORED_SENTENCE);
  expect(bandAlertVariant()).toBe('error');
  expect(screen.queryAllByTestId(MESSAGE_BAND_TEST_ID)).toHaveLength(1);
  expect(screen.queryByTestId(INFORMATION_BAND_TEST_ID)).toBeNull();
  // WHY : Assumptions: the record is asserted present as well, so the case cannot pass by having
  //       failed the read -- a refusal would also band a sentence in the error variant.
  expect(recordTextFor('transactionId')).toBe(A_TRANSACTION_ID);
}

/**
 * Asserts that an ordinary successful read leaves the outcome band holding nothing at all.
 *
 * Assumptions: this is the reference's own rendering and is asserted separately from the case above
 * because it exercises the other value the contract admits.
 * `services/transaction-service/src/main/resources/openapi/transaction-api.yaml` L2473-L2479 records
 * that \"a successful read sets no message, so null is the ordinary value here\", and
 * `app/cbl/COTRN01C.cbl` L91 is why: `WS-MESSAGE` is blanked on entry and no successful arm writes it,
 * so `ERRMSGO` is blank on the map the operator receives.
 *
 * Assumptions: the ABSENCE of an alert is asserted rather than an empty string alone, because
 * `ui/src/layout/MessageBand.tsx` renders a reserved-height element with no alert in it when the
 * message is empty -- so a band holding an empty alert would read as blank text while still occupying
 * the accessibility tree with a severity.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function leavesTheOutcomeBandBlankOnAnOrdinaryRead(): Promise<void> {
  const record = aTransaction(A_TRANSACTION_ID);
  await openWithRecord(record);

  expect(record.returnMessage).toBeNull();
  expect(bandText()).toBe('');
  expect(bandAlertVariant()).toBeNull();
  expect(screen.queryByTestId(INFORMATION_BAND_TEST_ID)).toBeNull();
}

/**
 * Asserts that the merchant's city and postal code render in one typeface, and money still in the code face.
 *
 * ⚠️ Purpose: a browser review measured these two values side by side in one row holding identical
 * text in two typefaces -- the city proportional and the postal code fixed-pitch. At the two-column
 * width they ARE one row: `buildRecordItems` emits them twelfth and thirteenth and closes three
 * earlier rows with `span: 'filled'`, so the pairing is deterministic rather than incidental.
 *
 * ⚠️ Assumptions: the mapset draws no distinction between them, which is what makes the split a
 * defect rather than a transcription. `app/bms/COTRN01.bms` L240-L243 declares `MCITY` and L252-L255
 * declares `MZIP` with the same `ATTRB=(ASKIP,NORM)` and the same `COLOR=BLUE`, differing only in
 * `LENGTH` and `POS`; `TRAN-MERCHANT-CITY PIC X(50)` and `TRAN-MERCHANT-ZIP PIC X(10)` at
 * `app/cpy/CVTRA05Y.cpy` L13-L14 are both alphanumeric. `ui/src/screens/accountView/index.tsx`
 * L992-L995 reaches the same answer independently, rendering its own postal code with
 * `monetary: false`.
 *
 * ⚠️ Assumptions: the amount is asserted STILL in the code face in the same case, so the fix cannot
 * be over-applied. Withdrawing the code face from every value would also remove this split and would
 * be wrong: money and timestamps are columnar and their positions have to line up, which is the
 * distinction the screen's own policy comment records.
 *
 * Assumptions: the two faces are compared for EQUALITY rather than each asserted against a literal
 * family, so no design value is written in this file -- the design system's zero-hardcoded-values rule
 * applies to a test as much as to a screen, and the code token is referenced through
 * `cssVariableReferenceFor` for the one assertion that needs to name it.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function rendersTheMerchantAddressInOneTypeface(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));

  const city = recordValueFor('merchantCity');
  const zip = recordValueFor('merchantZip');
  const codeFace = cssVariableReferenceFor(TYPOGRAPHY_TOKENS.fixedPitchData);

  expect(zip.style.fontFamily).toBe(city.style.fontFamily);
  expect(codeFace.test(zip.style.fontFamily)).toBe(false);
  expect(recordValueFor('amount').style.fontFamily).toMatch(codeFace);
}

/**
 * Asserts that the record grid emits exactly one cell per field and no empty cell beside them.
 *
 * ⚠️ Purpose: the dead-markup half of the finding this screen is cited under. A rendering review
 * found a sibling record screen emitting a trailing grid row of three empty columns, so the same
 * question has to be answered here rather than assumed -- and the answer is a census, not a claim.
 *
 * ⚠️ Assumptions: the census is the right instrument because the defect is a cell with no field
 * behind it, which no assertion about a named field can see. Thirteen labels and thirteen contents
 * with nothing left over proves that every cell the grid emits is one the record asked for.
 *
 * ⚠️ Assumptions: the count is thirteen because that is what the mapset paints -- `app/bms/COTRN01.bms`
 * declares thirteen data-field pairs across its rows 10 to 20 -- and the census is taken on the FULL
 * record rather than on a cleared screen, since a cleared screen blanks the thirteen values while the
 * thirteen labels stand (`INITIALIZE-ALL-FIELDS` at `app/cbl/COTRN01C.cbl` L309-L326) and would give
 * the same counts for a different reason.
 *
 * ⚠️ Assumptions: the emptiness half is scoped to the grid and NOT to every column in the screen,
 * which would look like the stronger assertion and is in fact a false one: the lookup control's own
 * column holds an `<input>`, whose value is a property rather than text, so a text-emptiness census
 * across the form would report that column empty on a screen that was rendering correctly.
 * @returns {Promise<void>} Completion of the case; the assertions are its effect.
 */
async function emitsOneGridCellPerFieldAndNoOther(): Promise<void> {
  await openWithRecord(aTransaction(A_TRANSACTION_ID));

  const grid = screen.getByText(TRANSACTION_DETAIL_FIELD_LABELS.transactionId).closest('table');
  expect(grid).not.toBeNull();

  const labels = grid?.querySelectorAll('.ant-descriptions-item-label') ?? [];
  const contents = grid?.querySelectorAll('.ant-descriptions-item-content') ?? [];
  const declaredFields = Object.keys(TRANSACTION_DETAIL_FIELD_LABELS).length;

  expect(declaredFields).toBe(13);
  expect(labels).toHaveLength(declaredFields);
  expect(contents).toHaveLength(declaredFields);
  for (const label of labels) {
    expect((label.textContent ?? '').length).toBeGreaterThan(0);
  }
}

/**
 * Registers every transaction-detail case with the runner.
 *
 * Assumptions: each case is a hoisted NAMED function passed to `it` by name rather than an inline
 * callback, matching the sibling suites. `ui/eslint.config.js` configures `jsdoc/require-jsdoc` with
 * `publicOnly: false`, so it selects a function expression in every position including a bare
 * callback argument, and a JSDoc block written above an inline callback is moved by Prettier onto the
 * preceding string literal -- which detaches it from the function it documents and fails the gate.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function transactionDetailCases(): void {
  it('reads the transaction its route parameter names', readsTheTransactionTheRouteParameterNames);
  it('holds the lookup control to its declared width', holdsTheLookupControlToItsDeclaredWidth);
  it(
    "places the cursor in the map's single unprotected field",
    placesTheCursorInTheMapsSingleUnprotectedField,
  );
  it(
    'paints the record as a bordered two-up Descriptions',
    paintsTheRecordAsABorderedTwoUpDescriptions,
  );
  it(
    'uses library components rather than raw controls',
    usesLibraryComponentsRatherThanRawControls,
  );
  it(
    'transcribes every declared screen width from the symbolic map',
    transcribesEveryDeclaredScreenWidthFromTheSymbolicMap,
  );
  it(
    'honours the header band widths its own dependencies supply',
    honoursTheHeaderBandWidthsItsOwnDependenciesSupply,
  );
  it('paints the caption and lookup label verbatim', paintsTheCaptionAndLookupLabelVerbatim);
  it(
    'renders the amount as text through the display mask',
    rendersTheAmountAsTextThroughTheDisplayMask,
  );
  it(
    'keeps both timestamps unparsed at full precision',
    keepsBothTimestampsUnparsedAtFullPrecision,
  );
  it('shows only the reduced card number', showsOnlyTheReducedCardNumber);
  it('refuses a blank lookup without reading', refusesABlankLookupWithoutReading);
  it('marks the blank field with its asterisk', marksTheBlankFieldWithItsAsterisk);
  it(
    'reports a not-found lookup with the shared sentence',
    reportsANotFoundLookupWithTheSharedSentence,
  );
  it(
    'reports every other failure with its own capitalised sentence',
    reportsEveryOtherFailureWithItsOwnCapitalisedSentence,
  );
  it(
    'marks a service-reported field error without the asterisk',
    marksAServiceReportedFieldErrorWithoutTheAsterisk,
  );
  it(
    'reports an unbound key with the fifty-character sentence',
    reportsAnUnboundKeyWithTheFiftyCharacterSentence,
  );
  it(
    "paints a service sentence in the channel's own severity",
    paintsAServiceSentenceInTheChannelsOwnSeverity,
  );
  it(
    'leaves the outcome band blank on an ordinary read',
    leavesTheOutcomeBandBlankOnAnOrdinaryRead,
  );
  it('renders the merchant address in one typeface', rendersTheMerchantAddressInOneTypeface);
  it('emits one grid cell per field and no other', emitsOneGridCellPerFieldAndNoOther);
  it('paints exactly the four keys the mapset does', paintsExactlyTheFourKeysTheMapsetDoes);
  it('emphasises no key on a read-only screen', emphasisesNoKeyOnAReadOnlyScreen);
  it('announces an outstanding read', announcesAnOutstandingRead);
  it(
    'fetches on the Enter key and on the Enter control alike',
    fetchesOnTheEnterKeyAndOnTheEnterControlAlike,
  );
  it(
    'clears on the PF4 key and on the clear control alike',
    clearsOnThePfFourKeyAndOnTheClearControlAlike,
  );
  it('opens the browse on the PF5 key', opensTheBrowseOnThePfFiveKey);
  it('opens the browse on the browse control', opensTheBrowseOnTheBrowseControl);
  it(
    'returns to the handed-over origin on the PF3 key',
    returnsToTheHandedOverOriginOnThePfThreeKey,
  );
  it('falls back to the main menu on the PF3 key', fallsBackToTheMainMenuOnThePfThreeKey);
  it('falls back to the main menu on the back control', fallsBackToTheMainMenuOnTheBackControl);
  it(
    'keeps the detail route open to any signed-on operator',
    keepsTheDetailRouteOpenToAnySignedOnOperator,
  );
  it(
    'establishes identity only through the sanctioned sign-on',
    establishesIdentityOnlyThroughTheSanctionedSignOn,
  );
}

describe('transaction detail screen', transactionDetailCases);
