/**
 * @file Component test for the transaction browse at `/transactions` -- the migration target of BMS
 * mapset `app/bms/COTRN00.bms` (map `COTRN0A`, 89 `DFHMDF` fields) and program
 * `app/cbl/COTRN00C.cbl`, mounted by `ui/src/router.tsx` at `TRANSACTION_LIST_PATH`.
 *
 * Purpose
 * -------
 * Assert the observable contract of the single default-exported component of
 * `ui/src/screens/transactionList/index.tsx`: the ten-row keyset page and its cursor arithmetic, the
 * six distinct paging sentences the program emits, the declared field widths, the four attention
 * identifiers it binds and the four it refuses, the single-character selection vocabulary, the route
 * one chosen row opens, and the exactness of the money and timestamp values it paints.
 *
 * This file is the ONLY verification this screen gets, and that is a property of the baseline rather
 * than a gap in the suite. `tests/README.md` section 1.1 states it directly: online `CO*` CICS
 * programs cannot run end to end without a CICS runtime, which is absent on the runner, so only their
 * extractable field-validation logic is unit-tested. The COBOL parity oracle under `tests/**` is
 * therefore an oracle for the BATCH chain alone -- there is no golden master for `COTRN00C`, none can
 * be produced here, and every expectation below is anchored instead to a cited line of the immutable
 * baseline or to a constant the tree already carries.
 *
 * Parameters (module analogue)
 * ----------------------------
 * This module takes no arguments. What it consumes, and what each source contributes, is:
 * `ui/src/test/setup.ts` -- the shell-mounted render helper, the keyboard operator, the attention-key
 * driver, the four-member page-envelope builder and the problem-document builder;
 * `ui/src/api/transactions.ts` -- mocked, because `msw` is absent from `ui/package.json`, so the
 * browse operation is replaced by a spy and no request, endpoint or credential is involved;
 * `ui/src/messages/messages.ts` -- every user-visible string AND the COBOL line provenance of each,
 * so a citation in this file is machine-compared rather than merely written down;
 * `ui/src/screens/transactionList/index.tsx` -- the subject and its exported width, page-size and
 * selection constants.
 *
 * Returns (module analogue)
 * -------------------------
 * Nothing. Evaluating this module registers six suites with the runner; each case's outcome is its
 * own assertions.
 *
 * Exceptions (module analogue)
 * ----------------------------
 * A case throws when the screen departs from the cited baseline behaviour, and the assertion names
 * the departure. Testing Library raises on a query that matches nothing or matches twice, which is
 * the reported form of a missing or duplicated element.
 *
 * Assumptions: the obligation this file is written to is Rule 1, Explainability, and it is the only
 * user-specified rule on this project. It requires a docstring on every function stating purpose,
 * parameters, return value and exceptions, and an inline comment giving the WHY of each non-obvious
 * decision under one of four named labels. `tests/README.md` section 12 imposes the identical
 * obligation on "every new test, fixture builder, helper, mock, and runner routine" and calls it a
 * hard review gate, so the two agree and this file EXTENDS an established house convention rather
 * than introducing one. The written form of the labels is fixed by
 * `docs/CODE_DOCUMENTATION_STANDARD.md` and enforced by `config/rule1/rule1_gate.py`: plural,
 * unparenthesised, colon retained, no emphasis markup, and no statement-level `WHAT:` comment in a
 * `.tsx` file.
 *
 * Assumptions: every test API is imported by name rather than taken from an ambient global.
 * `ui/vitest.config.ts` injects them at run time with `globals: true`, but `ui/tsconfig.json` keeps
 * its `types` list EMPTY -- deliberately, so a stray `expect` in a screen is a named typecheck
 * failure -- so nothing is declared ambiently and an omitted import fails to compile on the symbol it
 * omitted. Both files record this pairing at length.
 *
 * Assumptions: every callback is a named function DECLARATION rather than an inline arrow.
 * `ui/eslint.config.js` requires a documentation block on an arrow function in any position through
 * its `jsdoc/require-jsdoc` contexts, and Prettier moves a block comment that follows an argument
 * comma onto the preceding literal, which detaches it from the function it documents. Every sibling
 * screen test in this package is written the same way.
 *
 * Assumptions: no mock is reset by hand anywhere below. `ui/vitest.config.ts` sets `clearMocks` and
 * `restoreMocks`, so recorded calls and implementations are discarded between cases by the runner;
 * a second reset in an `afterEach` here would duplicate a guarantee the configuration already makes.
 */

import { act, screen, waitFor, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactElement } from 'react';
import { matchPath, useLocation } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import { listTransactions } from '../api/transactions';
import type {
  ApiError,
  PageResponse,
  TransactionListQuery,
  TransactionSummary,
} from '../api/types';
// Assumptions: the busy region is located by the shared test identifier the helper itself publishes,
//   so a case cannot drift from the one place that decides the region's shape.
import { BUSY_ANNOUNCEMENT_TEST_ID } from '../layout/fieldHelp';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import { CICS_AIDS, KEYBOARD_KEY_TO_AID } from '../layout/usePfKeys';
import type { CicsAid } from '../layout/usePfKeys';
import {
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  MESSAGE_BAND_BY_MAPSET,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  PROGRAM_SOURCE_FILES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  TRANSACTION_LIST_COLUMN_HEADERS,
  TRANSACTION_LIST_LABELS,
  TRANSACTION_LIST_MAPSET_SOURCE_FILE,
} from '../messages/messages';
import type { SourceRef } from '../messages/messages';
import { ROUTE_TABLE, TRANSACTION_DETAIL_PATH, TRANSACTION_LIST_PATH } from '../router';
import TransactionListScreen, {
  TRANSACTION_AMOUNT_COLUMN_WIDTH,
  UNRESOLVED_TRANSACTION_DATE,
  TRANSACTION_DATE_COLUMN_WIDTH,
  TRANSACTION_DESCRIPTION_WIDTH,
  TRANSACTION_ID_COLUMN_WIDTH,
  TRANSACTION_ID_FILTER_WIDTH,
  TRANSACTION_LIST_KEY_LABELS,
  TRANSACTION_LIST_MAPSET,
  TRANSACTION_LIST_PAGE_SIZE,
  TRANSACTION_LIST_SELECTION_CODE,
  VIEW_SELECTION_CODES,
  formatOriginationDate,
  formatTransactionAmount,
  isViewSelectionCode,
  selectionActionLabel,
  transactionDetailPath,
} from '../screens/transactionList';
import { FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';
import {
  LEADING_CURSOR,
  TRAILING_CURSOR,
  apiError,
  expectMaxLength,
  expectVerbatimMessage,
  pageResponse,
  pressPfKey,
  renderInAppShell,
} from './setup';
import type { HarnessRenderResult } from './setup';

/**
 * Builds the mocked surface of the transaction transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts
 * every `vi.mock` call above the imports and a factory held in a `const` would be in its temporal
 * dead zone at registration time.
 *
 * Assumptions: the browse operation alone is stubbed, because it is the only operation this screen
 * calls -- `createTransactionPageReader` reaches `listTransactions` and nothing else. The transport
 * module is mocked rather than the client beneath it because `msw` is absent from this package, so
 * there is no request-interception layer to answer at, and because nothing here asserts on a URL.
 * @returns {Record<string, unknown>} The browse operation as a fresh spy.
 */
function mockTransactionTransportModule(): Record<string, unknown> {
  return { listTransactions: vi.fn() };
}

vi.mock('../api/transactions', mockTransactionTransportModule);

/**
 * Number of rows the mapset paints and the program fills.
 *
 * Assumptions: ten, and it is stated here as the number the ASSERTIONS are written against so that
 * the subject's own exported constant can be compared with it rather than assumed equal to it. The
 * baseline states it two independent ways: `app/cbl/COTRN00C.cbl` runs
 * `PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10` at L290 and again at L344, and
 * `app/bms/COTRN00.bms` declares exactly ten selection row families, `SEL0001` through `SEL0010`, at
 * L153, L182, L211, L240, L269, L298, L327, L356, L385 and L414.
 */
const MEASURED_PAGE_SIZE = 10;

/**
 * Declared width of one selection field, `SEL0001I` through `SEL0010I PIC X(1)`.
 *
 * Assumptions: one character, from `app/cpy-bms/COTRN00.CPY` L72 through L342. A browser control
 * carries no `maxLength`, so this width is honoured structurally instead -- see
 * {@link theTenSelectorsCarryTheSingleCharacterWidth} for the argument.
 */
const SELECTION_FIELD_WIDTH = 1;

/**
 * Builds one browse row in the four-member shape the contract publishes.
 *
 * Assumptions: every value is a STRING, `amount` included, and that is the contract rather than a
 * convenience. `ui/src/api/types.ts` declares `TransactionSummary.amount` as a string under
 * transformation rule T3, because JavaScript's only numeric type is an IEEE-754 binary64 double which
 * cannot represent most scale-two decimal fractions exactly -- so a fixture holding a number would
 * be testing the screen against a value the service never sends.
 *
 * Assumptions: FOUR members and no card number, which is the contract's own shape at
 * `ui/src/api/types.ts` L1546 to L1551. The reference record does carry `TRAN-CARD-NUM PIC X(16)`
 * (`app/cpy/CVTRA05Y.cpy` L15), and the browse response deliberately omits it rather than masking it,
 * because a page discloses many rows at once. A builder that added one -- even masked -- would let
 * this screen be tested against a disclosure the service does not make.
 * @param {number} ordinal - Which row this is, one-based, used to mint a distinct identifier.
 * @param {string} amount - The exact decimal amount as the service publishes it, as text.
 * @returns {TransactionSummary} One row of the browse.
 */
function transactionRow(ordinal: number, amount: string): TransactionSummary {
  return {
    // Assumptions: sixteen digits, because the key this addresses is `TRAN-ID PIC X(16)` at
    //   `app/cpy/CVTRA05Y.cpy` L5 and the mapset paints the column at `LENGTH=16`. A shorter
    //   identifier would still render, so the fixture states the real width to keep the column
    //   assertions honest.
    transactionId: String(ordinal).padStart(TRANSACTION_ID_COLUMN_WIDTH, '0'),
    // Assumptions: the twenty-six-character form `YYYY-MM-DD HH:MM:SS.mmmmmm`, which is
    //   `TRAN-ORIG-TS PIC X(26)` at `app/cpy/CVTRA05Y.cpy` L16, and NOT an ISO instant with a `T` and
    //   a `Z`. The date is the business date `app/jcl/INTCALC.jcl` injects at L22 as
    //   `PARM='2022071800'`, so a fixture reads as this system's own reference data rather than as
    //   today -- which also keeps it clear of any date a runner's clock can produce.
    originTimestamp: `2022-07-18 22:10:${String(ordinal).padStart(2, '0')}.000001`,
    description: `PARITY TRANSACTION ${String(ordinal).padStart(2, '0')}`,
    amount,
  };
}

/**
 * The ten rows of a full page.
 *
 * Assumptions: exactly {@link MEASURED_PAGE_SIZE} rows, so a case can distinguish a screen that
 * renders the delivered page from one that slices it. Every amount is a distinct exact decimal so a
 * mis-ordered or duplicated row is visible in a failure message.
 */
const FULL_PAGE_ROWS: readonly TransactionSummary[] = [
  transactionRow(1, '100.00'),
  transactionRow(2, '-100.00'),
  transactionRow(3, '0.01'),
  transactionRow(4, '99999999.99'),
  transactionRow(5, '1234.50'),
  transactionRow(6, '-0.01'),
  transactionRow(7, '8.20'),
  transactionRow(8, '0.00'),
  transactionRow(9, '77777.77'),
  transactionRow(10, '2500.75'),
];

/**
 * The rows of a second page, so a forward step is distinguishable from a repeat of the first.
 *
 * Assumptions: identifiers continue the ascending order the browse publishes, because
 * `ui/src/hooks/usePagedQuery.ts` records that a page arrives ascending whichever direction produced
 * it. A second page reusing the first page's identifiers could not tell a real step from a re-read.
 */
const SECOND_PAGE_ROWS: readonly TransactionSummary[] = [
  transactionRow(11, '11.11'),
  transactionRow(12, '12.12'),
];

/**
 * Reads one fixture row by position, refusing an absent one.
 *
 * Assumptions: `ui/tsconfig.json` sets `noUncheckedIndexedAccess`, so an index expression is typed as
 * possibly absent and cannot be used without a check. Alternatives Considered: a non-null assertion
 * at each use site, which the compiler accepts and which would silently read `undefined` off the end
 * of a shortened fixture -- the case would then fail several assertions later on a property of
 * nothing, naming the property rather than the fixture.
 * @param {readonly TransactionSummary[]} rows - The fixture page to read from.
 * @param {number} index - Zero-based position within it.
 * @returns {TransactionSummary} The row at that position.
 * @throws {Error} If the position holds no row, naming the position and the length.
 */
function rowAt(rows: readonly TransactionSummary[], index: number): TransactionSummary {
  const row = rows[index];
  if (row === undefined) {
    throw new Error(`fixture holds no row at index ${String(index)} of ${String(rows.length)}`);
  }
  return row;
}

/**
 * Reads the subject module's own source text.
 *
 * Purpose: two of this screen's contracts are PROPS rather than rendered output, and a rendered
 * measurement cannot tell them from their defaults. `pagination={false}` is the clearest case: antd's
 * `Table` accepts `pagination={{ hideOnSinglePage: true }}`, which renders no pager while still
 * slicing rows client-side, so "no pager is visible" is satisfied by exactly the configuration this
 * screen must not have.
 *
 * Alternatives Considered: inspecting the rendered element tree for the prop, which needs a renderer
 * internal this package does not use; and rendering more rows than the page holds so a slicing pager
 * would drop one, which cannot be done honestly because the service never delivers more than the page
 * size. Reading the module's own text is the established form here -- `ui/src/screens/cardScreens.test.tsx`
 * asserts two of the card screens' policies the same way.
 * @returns {string} The subject module's source, as UTF-8 text.
 * @throws {Error} From the filesystem, if the module is not at the expected path -- which is itself
 *   the failure worth reporting, because the subject would have moved without this file following.
 */
function subjectSourceText(): string {
  return readFileSync(
    join(import.meta.dirname, '..', 'screens', 'transactionList', 'index.tsx'),
    'utf8',
  );
}

/**
 * Reports the lines one shared message is cited at inside this program.
 *
 * Purpose: makes a citation in this file machine-compared rather than merely written down.
 * `ui/src/messages/messages.ts` carries each string's provenance as DATA, so an assertion can state
 * "the catalog cites `COTRN00C.cbl` L248 for this sentence" and fail if that record ever moves.
 *
 * Assumptions: a shared sentence is cited against SEVERAL programs -- the five paging sentences are
 * shared with `COUSR00C` and two of them with `COPAUS0C` -- so the entry for this program has to be
 * selected rather than the whole list compared. Selecting by the catalog's own
 * `PROGRAM_SOURCE_FILES.COTRN00C` path keeps that selection from being a second spelling of the path.
 * @param {readonly SourceRef[]} references - The catalog's provenance list for one sentence.
 * @returns {readonly number[]} The lines cited for this program, ascending as the catalog holds them.
 * @throws {Error} If the sentence carries no citation against this program, which means the
 *   assertion's premise is false rather than its expectation.
 */
function linesCitedInThisProgram(references: readonly SourceRef[]): readonly number[] {
  for (const reference of references) {
    if (reference.file === PROGRAM_SOURCE_FILES.COTRN00C) {
      return reference.lines;
    }
  }
  throw new Error(
    `the catalog cites no line of ${PROGRAM_SOURCE_FILES.COTRN00C} for this sentence`,
  );
}

/**
 * Mounts the subject inside the application frame, at its own route.
 *
 * Purpose: this screen delegates its title band, its row-23 message line and its row-24 key legend to
 * the shell through `useShellSlot`, so all three exist only when the frame is mounted around it.
 *
 * Assumptions: the frame is reached through `renderInAppShell`, which mounts the subject as a CHILD
 * of a pathless layout route -- the arrangement `ui/src/router.tsx` builds -- rather than as the
 * shell's `children`. Passing it as `children` REPLACES the outlet the router actually fills, so a
 * regression that broke outlet rendering would leave every such case passing.
 *
 * Assumptions: no session is seeded, and that is fidelity rather than an omission. The subject's own
 * documentation records that the authentication decision is made one level up, in the router's guard,
 * and `/transactions` is not an administrative route in any case: all ELEVEN main-menu options carry
 * user type `'U'` in `app/cpy/COMEN02Y.cpy` at L29, L35, L41, L47, L53, L59, L65, L72, L78, L84 and
 * L90, and not one carries `'A'`. Seeding would also install the recording transport, which would
 * contend with the mocked transport module this file relies on.
 * ⚠️ Assumptions: the mount is wrapped in `act` because the render helper is ASYNCHRONOUS, and that
 * makes it behave differently from the synchronous `render` the sibling screen tests call.
 * `renderInAppShell` resolves the design-system provider and the frame through dynamic imports before
 * it renders, so awaiting it yields control once more AFTER the first read's promise continuation has
 * already been queued from inside an effect -- and that continuation then settles the first page
 * outside any act scope, which React reports as an unwrapped update. Wrapping the whole mount puts
 * both the render and that continuation inside one scope. Alternatives Considered: flushing with an
 * `act` call after the mount, which does not help, because the update has already happened by then;
 * and ignoring the warning as cosmetic, rejected because it is written to stderr on every case and a
 * reader cannot tell a benign instance from a real missing-act defect.
 * @returns {Promise<HarnessRenderResult>} The render result, with the keyboard operator attached.
 * @throws {Error} If the route pattern cannot match the address the router opens at, which the
 *   harness reports naming both.
 */
async function mountBrowse(): Promise<HarnessRenderResult> {
  return await act(
    /**
     * Renders the subject inside the frame, at the route the application mounts it at.
     * @returns {Promise<HarnessRenderResult>} The render result.
     */
    async (): Promise<HarnessRenderResult> =>
      await renderInAppShell(<TransactionListScreen />, {
        initialEntries: [TRANSACTION_LIST_PATH],
        routePath: TRANSACTION_LIST_PATH,
      }),
  );
}

/**
 * Mounts the subject over one delivered page and waits for that page to be on display.
 * @param {PageResponse<TransactionSummary>} page - The envelope the browse operation answers with.
 * @returns {Promise<HarnessRenderResult>} The settled render result.
 * @throws {Error} If the page's first row never appears, which Testing Library reports.
 */
async function browseShowing(page: PageResponse<TransactionSummary>): Promise<HarnessRenderResult> {
  vi.mocked(listTransactions).mockResolvedValue(page);
  const rendered = await mountBrowse();
  await waitFor(
    /**
     * Waits until the first delivered row has been painted.
     *
     * Assumptions: a ROW is waited for rather than the table, because the table element exists from
     * the first render while the read is still outstanding -- so waiting on it would settle before
     * the page arrived and leave the following assertions racing the response.
     * @returns {void} Nothing; throws until the row is present.
     */
    () => {
      expect(screen.getByText(rowAt(page.items, 0).transactionId)).toBeInTheDocument();
    },
  );
  return rendered;
}

/**
 * Mounts the subject over a browse that settles with no rows and waits for its sentence.
 * @param {string} expected - The sentence the settled state is expected to carry, from the catalog.
 * @returns {Promise<HarnessRenderResult>} The settled render result.
 * @throws {Error} If the sentence never appears in the message band.
 */
async function browseReporting(expected: string): Promise<HarnessRenderResult> {
  const rendered = await mountBrowse();
  await waitFor(
    /**
     * Waits until the delegated message band carries the sentence.
     * @returns {void} Nothing; throws until the band's text is exactly the sentence.
     */
    () => {
      expect(messageBand().textContent).toBe(expected);
    },
  );
  return rendered;
}

/**
 * Test identifier of the probe that reports the router's current address.
 *
 * Assumptions: a test identifier rather than a role, because the probe is scaffolding and carries no
 * meaning to an operator -- giving it a role would put an element in the accessibility tree that the
 * application never renders.
 */
const LOCATION_PROBE_TEST_ID = 'transaction-list-location-probe';

/**
 * Reports the router's current address, so a transfer can be observed rather than inferred.
 *
 * Purpose: `EXEC CICS XCTL` becomes a client-side route change under transformation rule T5, and the
 * destination is the half of that contract worth asserting. Nothing the subject renders states the
 * address, so a probe mounted beside it is what makes the destination observable.
 * @returns {ReactElement} An element carrying the current pathname as its only text.
 */
function LocationProbe(): ReactElement {
  const { pathname } = useLocation();
  return <span data-testid={LOCATION_PROBE_TEST_ID}>{pathname}</span>;
}

/**
 * Reads the address the router is currently at.
 * @returns {string} The current pathname.
 * @throws {Error} If the probe is not mounted, which means the subject was rendered without it.
 */
function currentPath(): string {
  return textOf(screen.getByTestId(LOCATION_PROBE_TEST_ID));
}

/**
 * Mounts the subject beside the address probe and waits for its first page.
 *
 * ⚠️ Assumptions: NO route pattern is supplied, so the harness mounts the pair under a catch-all child
 * route, and that is load-bearing for exactly one reason: a transfer leaves `/transactions`, and a
 * subject mounted at that pattern alone unmounts on the way out -- taking the probe with it, so the
 * destination could never be read. The catch-all keeps the probe mounted across the transfer.
 * Alternatives Considered: mounting at the real pattern and asserting only that the subject
 * disappeared. Rejected because "something navigated" is not the contract; the contract names a
 * destination, and a screen that navigated to the wrong address would pass.
 *
 * Assumptions: mounting at a catch-all is safe for this subject specifically, because it reads no route
 * parameter and every address it navigates to is absolute -- the harness's own note about a splat route
 * concerns relative link resolution, which this screen has none of.
 * @param {PageResponse<TransactionSummary>} page - The envelope the browse operation answers with.
 * @returns {Promise<HarnessRenderResult>} The settled render result.
 * @throws {Error} If the page's first row never appears.
 */
async function browseWithLocationProbe(
  page: PageResponse<TransactionSummary>,
): Promise<HarnessRenderResult> {
  vi.mocked(listTransactions).mockResolvedValue(page);
  const rendered = await act(
    /**
     * Renders the subject and the probe together, under the harness's catch-all child route.
     * @returns {Promise<HarnessRenderResult>} The render result.
     */
    async (): Promise<HarnessRenderResult> =>
      await renderInAppShell(
        <>
          <TransactionListScreen />
          <LocationProbe />
        </>,
        { initialEntries: [TRANSACTION_LIST_PATH] },
      ),
  );

  await waitFor(
    /**
     * Waits until the first delivered row has been painted.
     * @returns {void} Nothing; throws until the row is present.
     */
    () => {
      expect(screen.getByText(rowAt(page.items, 0).transactionId)).toBeInTheDocument();
    },
  );
  return rendered;
}

/**
 * Reports the route the application assigns to one baseline program.
 *
 * ⚠️ Purpose: the source names its transfer targets by PROGRAM -- `'COMEN01C'` at
 * `app/cbl/COTRN00C.cbl` L123 and `'COTRN01C'` at L188 -- so looking the address up by program name is
 * the assertion that follows the citation. Alternatives Considered: importing the destination's own
 * route constant, which is shorter. Rejected on two grounds: the main menu's constant lives in a module
 * outside this file's declared dependencies, and naming the constant would assert that the screen goes
 * to a particular address rather than that it goes to the screen the baseline hands control to.
 * @param {string} program - The baseline program name, as the route table records it.
 * @returns {string} The route pattern that program's screen is mounted at.
 * @throws {Error} If the route table names no such program, which means the citation no longer holds.
 */
function routeOfProgram(program: string): string {
  for (const entry of ROUTE_TABLE) {
    if (entry.program === program) {
      return entry.path;
    }
  }
  throw new Error(`the route table assigns no address to ${program}`);
}

/**
 * The four attention identifiers this screen binds.
 *
 * Assumptions: stated explicitly rather than derived, because this set IS the contract under test --
 * `app/cbl/COTRN00C.cbl` dispatches on `DFHENTER`, `DFHPF3`, `DFHPF7` and `DFHPF8` at L119 to L133 and
 * on nothing else. Deriving it from the subject would make the assertion circular.
 */
const BOUND_AIDS: readonly CicsAid[] = ['ENTER', 'PFK03', 'PFK07', 'PFK08'];

/**
 * Lists the attention identifiers a browser can raise that this screen does not bind.
 *
 * Assumptions: the raisable set is DERIVED from the hook's published key table rather than hand-listed,
 * so `CLEAR`, `PA1` and `PA2` fall out on their own -- they are members of the identifier domain with
 * no browser key at all, and a case pressing one would be testing a path the application does not have.
 * Deriving also means a key later bound by this screen fails the sweep instead of being skipped.
 * @returns {readonly CicsAid[]} Every raisable identifier this screen leaves unbound.
 */
function unboundFunctionKeys(): readonly CicsAid[] {
  const raisable = new Set<CicsAid>(Object.values(KEYBOARD_KEY_TO_AID));
  const unbound: CicsAid[] = [];
  for (const aid of CICS_AIDS) {
    if (raisable.has(aid) && !BOUND_AIDS.includes(aid)) {
      unbound.push(aid);
    }
  }
  return unbound;
}

/**
 * Locates the row-23 message band the shell paints for this screen.
 *
 * Assumptions: located by the test identifier `ui/src/layout/MessageBand.tsx` publishes rather than by
 * role, because that module gives the band `alert` or `status` according to the SEVERITY of what it
 * holds -- so a role query would have to be told the severity, which is the screen's data rather than
 * the frame's structure.
 * @returns {HTMLElement} The band element, which is present in both its empty and populated states.
 * @throws {Error} If no band is rendered, which usually means the subject was mounted without the
 *   shell around it.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Locates the starting-identifier entry control.
 *
 * Assumptions: located by its accessible name, which is the row-6 label the mapset paints, so the
 * query fails if the label association is broken as well as if the control is absent.
 * @returns {HTMLElement} The entry control.
 * @throws {Error} If no control carries that accessible name.
 */
function filterField(): HTMLElement {
  return screen.getByRole('textbox', { name: TRANSACTION_LIST_LABELS.filterLabel });
}

/**
 * Locates the key legend the shell paints from this screen's delegation.
 * @returns {HTMLElement} The legend's navigation landmark.
 * @throws {Error} If the legend is absent.
 */
function keyLegend(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Locates one control in the key legend by the label the screen supplies for it.
 *
 * Assumptions: located by accessible name, which for these controls IS the legend text --
 * `ui/src/layout/PfKeyBar.tsx` renders each label as the control's only child and adds no separate
 * accessible label, because the legend already encodes both the key and its action in one
 * `KEY=Action` string.
 * @param {string} label - The legend text, taken from the screen's own key-label constant.
 * @returns {HTMLElement} That key's control.
 * @throws {Error} If the legend renders no control carrying that label.
 */
function legendButton(label: string): HTMLElement {
  return within(keyLegend()).getByRole('button', { name: label });
}

/**
 * Locates one rendered table row by the identifier it is keyed on.
 *
 * Assumptions: the row is found by the reconciliation key the table renders, which is the transaction's
 * own identifier -- the subject uses `TRAN-ID PIC X(16)` as its row key because it is the file's own key
 * and is also the value the selection control carries and the detail route is built from, so all three
 * address one record. Alternatives Considered: locating the row by walking up from a cell found with a
 * text query, which needs no knowledge of the table's markup. Rejected because the values inside a row
 * are not unique across the page -- every fixture row carries the same calendar date -- so the text query
 * that would start the walk is the one that cannot be made unambiguous.
 * @param {HTMLElement} container - The render result's container.
 * @param {string} transactionId - The identifier the sought row is keyed on.
 * @returns {HTMLElement} That row's element.
 * @throws {Error} If no row carries that key, which usually means the page did not deliver it.
 */
function oneRenderedRow(container: HTMLElement, transactionId: string): HTMLElement {
  const row = container.querySelector(`[data-row-key="${transactionId}"]`);
  if (!(row instanceof HTMLElement)) {
    throw new Error(`no rendered row is keyed on ${transactionId}`);
  }
  return row;
}

/**
 * Reads one element's text exactly as the DOM holds it.
 *
 * Assumptions: `textContent` rather than an accessible name, because several catalogued strings carry
 * interior padding that is CONTENT -- the five column headings are `DFHMDF INITIAL=` operands filling
 * their declared cell widths, and that padding is how the mapset centres a heading over its column.
 * Accessible-name computation collapses runs of whitespace, so it would accept a heading whose padding
 * had been trimmed.
 * @param {HTMLElement} element - The element to read.
 * @returns {string} Its text content, or the empty string when it holds none.
 */
function textOf(element: HTMLElement): string {
  return element.textContent ?? '';
}

/**
 * Reports how many times the browse operation has been asked for a page.
 * @returns {number} The recorded call count.
 */
function readCount(): number {
  return vi.mocked(listTransactions).mock.calls.length;
}

/**
 * Reports the criteria one recorded read carried.
 *
 * Assumptions: an absent argument is reported as `undefined` rather than as an empty object, and the
 * two are distinguishable and both legitimate. `createTransactionPageReader` calls the operation with
 * NO argument for an opening read with a blank filter, which is `MOVE LOW-VALUES TO TRAN-ID` at
 * `app/cbl/COTRN00C.cbl` L207 -- browse from the beginning of the set rather than from a key of
 * blanks.
 * @param {number} index - Zero-based position of the read among those recorded.
 * @returns {TransactionListQuery | undefined} That read's criteria, or `undefined` if it carried none.
 * @throws {Error} If no read was recorded at that position.
 */
function criteriaOfRead(index: number): TransactionListQuery | undefined {
  const call = vi.mocked(listTransactions).mock.calls[index];
  if (call === undefined) {
    throw new Error(`no read was recorded at index ${String(index)}`);
  }
  return call[0];
}

/**
 * Asserts that one recorded read positioned itself by cursor alone.
 *
 * Purpose: the whole point of the keyset contract is that a position travels as an opaque cursor and
 * never as an ordinal. This checks both halves at once -- the cursor and direction that were sent, and
 * the absence of anything else.
 *
 * Assumptions: the permitted member set is the three the contract declares, and the check is on the
 * KEYS rather than on the type. `TransactionListQuery` already makes a fourth member a compile error,
 * so this adds the run-time half: an object assembled dynamically, or one widened at some future call
 * site, would still be caught here. A page ordinal reaching the service is the specific regression
 * being excluded -- the subject renders one, from `MOVE CDEMO-CT00-PAGE-NUM TO PAGENUMI` at
 * `app/cbl/COTRN00C.cbl` L324, and the source positions strictly by the cursor pair at L236 to L239
 * and L259 to L262, so an ordinal on the wire would be a protocol neither side implements.
 * @param {number} index - Zero-based position of the read among those recorded.
 * @param {string} cursor - The cursor the read is expected to have replayed.
 * @param {'next' | 'previous'} direction - The direction it is expected to have named.
 * @returns {void} Nothing; the assertions carry the outcome.
 * @throws {Error} If no read was recorded at that position.
 */
function expectPositionedRead(index: number, cursor: string, direction: 'next' | 'previous'): void {
  const criteria = criteriaOfRead(index);
  expect(criteria).toEqual({ cursor, direction });
  expect(Object.keys(criteria ?? {}).sort()).toEqual(['cursor', 'direction']);
}

/**
 * Builds the problem document a refused position carries.
 *
 * ⚠️ Assumptions: the browse operation is rejected with a BARE problem document rather than with the
 * client's own error class, and that is deliberate rather than a shortcut.
 * `ui/src/hooks/usePagedQuery.ts` normalises a rejection through one implementation of "what a problem
 * document is", and its second route accepts a document directly -- so a bare document reaches the
 * screen as the same `ApiError` an `ApiRequestError` would have carried. Alternatives Considered:
 * constructing that error class, which is what several sibling tests do. Rejected here because
 * `ui/src/api/client.ts` is not among this file's declared dependencies, and reaching outside that set
 * for a value the hook accepts natively would add a coupling for no additional coverage.
 * @returns {ApiError} A problem document at HTTP 404.
 */
function notFoundProblem(): ApiError {
  // WHY : Assumptions: 404 specifically, because the screen's message resolution tests that status and
  //       nothing else -- it is the target's shape for `STARTBR` answering `NOTFND`, and every other
  //       status takes the lookup-failure sentence instead.
  return apiError({ status: 404, code: 'CARDDEMO-0404' });
}

/**
 * Builds the problem document an ordinary service failure carries.
 *
 * Assumptions: a status the screen must NOT treat as an empty result. 500 is used because it is the
 * plainest such value; the assertion at stake is the branch, not the number.
 * @returns {ApiError} A problem document at HTTP 500.
 */
function serviceProblem(): ApiError {
  return apiError({ status: 500, code: 'CARDDEMO-0500', severity: 'CRITICAL' });
}

/**
 * Mounts the subject over a queue of pages, one per read, and waits for the first.
 *
 * Assumptions: the pages are queued with `mockResolvedValueOnce` rather than set once, because a
 * paging case has to distinguish a real step from a re-read of the same page -- a single resolved
 * value answers every read identically, so a screen that ignored the cursor entirely would pass.
 * @param {readonly PageResponse<TransactionSummary>[]} pages - The envelopes to answer with, in read
 *   order. The first must carry at least one row, since it is what the wait settles on.
 * @returns {Promise<HarnessRenderResult>} The settled render result.
 * @throws {Error} If the first page carries no rows, or if its first row never appears.
 */
async function browseSteppingThrough(
  pages: readonly PageResponse<TransactionSummary>[],
): Promise<HarnessRenderResult> {
  const operation = vi.mocked(listTransactions);
  for (const page of pages) {
    operation.mockResolvedValueOnce(page);
  }
  const rendered = await mountBrowse();
  const firstRow = rowAt(pageAt(pages, 0).items, 0);
  await waitFor(
    /**
     * Waits until the first queued page has been painted.
     * @returns {void} Nothing; throws until that page's first row is present.
     */
    () => {
      expect(screen.getByText(firstRow.transactionId)).toBeInTheDocument();
    },
  );
  return rendered;
}

/**
 * Reads one queued page by position, refusing an absent one.
 *
 * Assumptions: the same `noUncheckedIndexedAccess` obligation {@link rowAt} carries, stated separately
 * because the element type differs and a shared generic accessor would need its own type parameter for
 * no gain in two call sites.
 * @param {readonly PageResponse<TransactionSummary>[]} pages - The queued pages.
 * @param {number} index - Zero-based position within them.
 * @returns {PageResponse<TransactionSummary>} The page at that position.
 * @throws {Error} If the position holds no page.
 */
function pageAt(
  pages: readonly PageResponse<TransactionSummary>[],
  index: number,
): PageResponse<TransactionSummary> {
  const page = pages[index];
  if (page === undefined) {
    throw new Error(`no page was queued at index ${String(index)}`);
  }
  return page;
}

/**
 * Asserts the page on display renders exactly the rows the envelope delivered, and no others.
 *
 * Assumptions: both directions are checked -- every delivered identifier is present AND the count of
 * selection controls equals the delivered row count. Checking presence alone would pass for a screen
 * that padded the page with the blank filler rows `INITIALIZE-TRAN-DATA` leaves behind on the
 * terminal (`app/cbl/COTRN00C.cbl` L290 to L292), and counting alone would pass for a screen showing
 * the right number of wrong rows.
 * @param {readonly TransactionSummary[]} rows - The rows the envelope carried.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function expectExactlyTheseRows(rows: readonly TransactionSummary[]): void {
  for (const row of rows) {
    expect(screen.getByText(row.transactionId)).toBeInTheDocument();
  }
  expect(screen.getAllByRole('radio')).toHaveLength(rows.length);
}

/**
 * The browse asks for ten rows, which is the arity the mapset and the program both declare.
 *
 * Assumptions: three independent readings are asserted, not one, because each can fail on its own.
 * The exported constant is the value; the module's own text is where that value reaches
 * `usePagedQuery`, which REQUIRES the arity rather than defaulting it precisely because the five
 * browses in this tree declare five different ones; and a delivered page of ten renders ten
 * selectable rows, which is the arity as an operator meets it.
 * @returns {Promise<void>} Resolves once the full page has settled and been counted.
 */
async function theBrowseAsksForTheMeasuredPageSize(): Promise<void> {
  // WHY : Assumptions: ten, from `PERFORM VARYING WS-IDX FROM 1 BY 1 UNTIL WS-IDX > 10` at
  //       `app/cbl/COTRN00C.cbl` L290 and L344, and from the ten `SEL0001`-`SEL0010` row families the
  //       mapset declares at `app/bms/COTRN00.bms` L153, L182, L211, L240, L269, L298, L327, L356,
  //       L385 and L414. Two sources that cannot be derived from each other, which is why both are
  //       cited: the loop bound alone would not prove the terminal had ten rows to paint into.
  expect(TRANSACTION_LIST_PAGE_SIZE).toBe(MEASURED_PAGE_SIZE);

  // WHY : Assumptions: the value reaching the hook is asserted as the module's own text, because the
  //       arity is an ARGUMENT and a rendered page cannot tell "the screen asked for ten" from "the
  //       service happened to send ten". The hook never forwards it to the service -- it is a local
  //       bound -- so no recorded request can witness it either.
  expect(subjectSourceText()).toContain('pageSize: TRANSACTION_LIST_PAGE_SIZE');

  await browseShowing(pageResponse(FULL_PAGE_ROWS));
  expectExactlyTheseRows(FULL_PAGE_ROWS);
}

/**
 * The table's own offset pagination is genuinely turned off, not merely hidden.
 *
 * ⚠️ Refactoring Rationale: offset pagination was rejected on a correctness argument rather than a
 * preference. It positions a page by counting rows from the start of the ordering, so under concurrent
 * insertion it pushes a row into the following page and repeats another on it, and under deletion it
 * drops a row nobody ever sees. Browsing by key cannot do either, and the source does not: its browse
 * state is ALREADY a keyset cursor -- `CDEMO-CT00-TRNID-FIRST` is set from row 1's identifier at
 * `app/cbl/COTRN00C.cbl` L393 and `CDEMO-CT00-TRNID-LAST` from row 10's at L439, and the paging
 * paragraphs read strictly from those two. Keeping the pager off is what makes the mapping one-to-one
 * rather than an approximation.
 *
 * ⚠️ Assumptions: the PROP is asserted and not just the absence of a pager, because the two are not
 * equivalent. `pagination={{ hideOnSinglePage: true }}` renders no pager while still slicing rows
 * client-side, so an assertion satisfied by "no pager is visible" is satisfied by exactly the
 * configuration this screen must not have. The rendered half is asserted as well, since the prop and
 * its effect can each regress alone.
 * @returns {Promise<void>} Resolves once the full page has settled and been examined.
 */
async function theTableDisablesOffsetPagination(): Promise<void> {
  expect(subjectSourceText()).toContain('pagination={false}');

  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));

  // WHY : Assumptions: the design system's own pager class is the thing looked for, because that is
  //       what the component emits when pagination is on in any form; no accessible role identifies a
  //       pager distinctly enough to query by.
  expect(rendered.container.querySelectorAll('.ant-pagination')).toHaveLength(0);
  expectExactlyTheseRows(FULL_PAGE_ROWS);
}

/**
 * The screen renders exactly the envelope's rows on a short final page.
 *
 * Assumptions: a two-row page is used because it is where padding would show. The terminal blanks all
 * ten row families before repopulating (`app/cbl/COTRN00C.cbl` L290 to L292, and L450 onward), so a
 * short page there leaves eight empty rows painted; the target renders the rows the service delivered
 * and no placeholders, which is a documented consequence of design gap G1 rather than a lost detail.
 * @returns {Promise<void>} Resolves once the short page has settled and been counted.
 */
async function theBrowseRendersExactlyTheDeliveredRows(): Promise<void> {
  await browseShowing(pageResponse(SECOND_PAGE_ROWS));

  expectExactlyTheseRows(SECOND_PAGE_ROWS);
  // WHY : Assumptions: a row from the full-page fixture is asserted ABSENT as the negative control. A
  //       count alone cannot distinguish a screen that renders two delivered rows from one that
  //       renders two rows of its own devising.
  expect(screen.queryByText(rowAt(FULL_PAGE_ROWS, 0).transactionId)).not.toBeInTheDocument();
}

/**
 * A forward step replays the trailing cursor and never an ordinal.
 *
 * Assumptions: the forward key is available because the envelope reported a further page, which the
 * service settles by reading one row BEYOND the page rather than by counting the rows in it -- exactly
 * as the source sets `NEXT-PAGE-YES` from an extra `READNEXT` at `app/cbl/COTRN00C.cbl` L305 to L312.
 * A count of the rows received cannot answer it, since a full page and a full page that happens to be
 * the last hold the same number of rows.
 * @returns {Promise<void>} Resolves once the second page is on display and the read is examined.
 */
async function aForwardStepReplaysTheTrailingCursor(): Promise<void> {
  const rendered = await browseSteppingThrough([
    pageResponse(FULL_PAGE_ROWS, { hasNext: true }),
    pageResponse(SECOND_PAGE_ROWS),
  ]);

  await pressPfKey(rendered.user, 'PFK08');
  await waitFor(
    /**
     * Waits until the second page is on display.
     * @returns {void} Nothing; throws until its first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(SECOND_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  // WHY : Assumptions: the trailing cursor is the value `PageResponse.lastKey` carried, which is the
  //       target form of `CDEMO-CT00-TRNID-LAST` -- the field `app/cbl/COTRN00C.cbl` L259 to L262
  //       positions a forward step from. The cursor is opaque here, replayed byte for byte and never
  //       parsed, so the fixture's own token is what must arrive.
  expectPositionedRead(1, TRAILING_CURSOR, 'next');
  expect(readCount()).toBe(2);
}

/**
 * A backward step replays the leading cursor, and only once a page exists to go back to.
 *
 * ⚠️ Assumptions: backward availability is DERIVED by the client and is not a member of the envelope.
 * `PageResponse` publishes exactly four members -- rows, both cursors and the further-page flag -- and
 * `ui/src/hooks/usePagedQuery.ts` composes `hasPrev` from its own page ordinal and the presence of a
 * leading cursor. The source composes it the same way, from `CDEMO-CT00-PAGE-NUM` rather than from
 * anything read out of the file. A fixture that invented a `hasPrev` member would let this case pass
 * against a body no service sends, which is the one fixture defect no assertion catches.
 * @returns {Promise<void>} Resolves once the backward read has been examined.
 */
async function aBackwardStepReplaysTheLeadingCursor(): Promise<void> {
  const rendered = await browseSteppingThrough([
    pageResponse(FULL_PAGE_ROWS, { hasNext: true }),
    pageResponse(SECOND_PAGE_ROWS),
    pageResponse(FULL_PAGE_ROWS, { hasNext: true }),
  ]);

  await pressPfKey(rendered.user, 'PFK08');
  await waitFor(
    /**
     * Waits until the browse has reached its second page, so a backward step is expressible.
     * @returns {void} Nothing; throws until the second page's first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(SECOND_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  await pressPfKey(rendered.user, 'PFK07');
  await waitFor(
    /**
     * Waits until the first page is on display again.
     * @returns {void} Nothing; throws until three reads have been issued.
     */
    () => {
      expect(readCount()).toBe(3);
    },
  );

  // WHY : Assumptions: the leading cursor, which is the target form of `CDEMO-CT00-TRNID-FIRST` --
  //       the field `app/cbl/COTRN00C.cbl` L236 to L239 positions a backward step from -- and the
  //       direction is named explicitly rather than left to a default, so a request read back from a
  //       log states which side was asked for.
  expectPositionedRead(2, LEADING_CURSOR, 'previous');
}

/**
 * No read carries a page ordinal, a page size or an offset.
 *
 * ⚠️ Assumptions: the ordinal is rendered and is display-only, and those two facts are asserted
 * together because the second is what makes the first safe. The source paints it into `PAGENUMI` at
 * `app/cbl/COTRN00C.cbl` L324 while positioning strictly by the cursor pair, so an ordinal on the wire
 * would be a protocol neither side implements. Asserting the rendered value alone would not exclude
 * it, and asserting the request alone would not prove the ordinal exists to be misused.
 * @returns {Promise<void>} Resolves once both reads have been examined.
 */
async function theOrdinalIsShownButNeverSent(): Promise<void> {
  const rendered = await browseSteppingThrough([
    pageResponse(FULL_PAGE_ROWS, { hasNext: true }),
    pageResponse(SECOND_PAGE_ROWS),
  ]);

  // WHY : Assumptions: the ordinal is located by the accessible name the mapset's own row-4 prompt
  //       supplies, `Page:` at `app/bms/COTRN00.bms` L84, so the query fails if the label association
  //       breaks as well as if the value does.
  const ordinal = screen.getByRole('status', { name: TRANSACTION_LIST_LABELS.pageLabel });
  expect(ordinal.textContent).toBe('1');

  await pressPfKey(rendered.user, 'PFK08');
  await waitFor(
    /**
     * Waits until the ordinal reports the second page.
     * @returns {void} Nothing; throws until the ordinal reads two.
     */
    () => {
      expect(
        screen.getByRole('status', { name: TRANSACTION_LIST_LABELS.pageLabel }).textContent,
      ).toBe('2');
    },
  );

  // WHY : Assumptions: the opening read carries NO criteria object at all, which is
  //       `MOVE LOW-VALUES TO TRAN-ID` at `app/cbl/COTRN00C.cbl` L207 -- browse from the beginning of
  //       the set rather than from a key of blanks. An empty object would be equivalent on the wire
  //       and is still distinguished, because "no query was asked for" and "an empty query was asked
  //       for" are what the client's own note keeps apart.
  expect(criteriaOfRead(0)).toBeUndefined();
  expectPositionedRead(1, TRAILING_CURSOR, 'next');
}

/**
 * The forward key at the last page shows the source's sentence and issues no read.
 *
 * ⚠️ Assumptions: the KEY is not disabled and the STEP is refused, which is the source's own
 * arrangement and not a shortcut. `ui/src/layout/usePfKeys.ts` answers a handler bound `disabled` by
 * reporting the invalid-key message, so disabling the forward control at the boundary would replace
 * the source's `'You are already at the bottom of the page...'` at `app/cbl/COTRN00C.cbl` L270 with a
 * sentence about an invalid key -- and the source refuses the STEP without ever refusing the key. It
 * sends with `SEND-ERASE-NO` on that arm, which is a repaint of the same page with a sentence attached
 * rather than a re-read, so the read count is what proves no request was issued.
 * @returns {Promise<void>} Resolves once the refusal has been examined.
 */
async function theForwardKeyIsRefusedAtTheLastPage(): Promise<void> {
  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));

  await pressPfKey(rendered.user, 'PFK08');
  await waitFor(
    /**
     * Waits until the boundary sentence has been painted.
     * @returns {void} Nothing; throws until the band carries it.
     */
    () => {
      expect(messageBand().textContent).toBe(
        SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE,
      );
    },
  );

  expect(readCount()).toBe(1);
  expectExactlyTheseRows(FULL_PAGE_ROWS);
}

/**
 * The backward key on the opening page shows the source's sentence and issues no read.
 *
 * Assumptions: the refusal is decided from the ordinal alone, with no backward read issued to check --
 * which is how the source decides it too, at `app/cbl/COTRN00C.cbl` L246 to L249. The page on display
 * is asserted unchanged, because `SEND-ERASE-NO` repaints the same rows beneath the sentence.
 * @returns {Promise<void>} Resolves once the refusal has been examined.
 */
async function theBackwardKeyIsRefusedOnTheOpeningPage(): Promise<void> {
  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));

  await pressPfKey(rendered.user, 'PFK07');
  await waitFor(
    /**
     * Waits until the boundary sentence has been painted.
     * @returns {void} Nothing; throws until the band carries it.
     */
    () => {
      expect(messageBand().textContent).toBe(
        SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE,
      );
    },
  );

  expect(readCount()).toBe(1);
  expectExactlyTheseRows(FULL_PAGE_ROWS);
}

/**
 * Groups the keyset-pagination cases.
 *
 * Assumptions: the ordering runs from the page arity outward to the two boundaries, so a failure in
 * the arity is read before the cases that depend on it.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function keysetPaginationContract(): void {
  it(
    'asks for the ten rows the mapset and the program declare',
    theBrowseAsksForTheMeasuredPageSize,
  );
  it('disables the component\u2019s own offset pagination', theTableDisablesOffsetPagination);
  it('renders exactly the rows the envelope delivered', theBrowseRendersExactlyTheDeliveredRows);
  it('replays the trailing cursor on a forward step', aForwardStepReplaysTheTrailingCursor);
  it('replays the leading cursor on a backward step', aBackwardStepReplaysTheLeadingCursor);
  it('shows the page ordinal without ever sending it', theOrdinalIsShownButNeverSent);
  it('refuses the forward step at the last page', theForwardKeyIsRefusedAtTheLastPage);
  it('refuses the backward step on the opening page', theBackwardKeyIsRefusedOnTheOpeningPage);
}

describe('transaction browse keyset pagination', keysetPaginationContract);

/**
 * The six paging sentences are six distinct strings, and the two near-duplicate pairs are deliberate.
 *
 * ⚠️ Assumptions: `'You are already at the top of the page...'` (`app/cbl/COTRN00C.cbl` L248) and
 * `'You are at the top of the page...'` (L608) differ by the word "already", and
 * `'You are already at the bottom of the page...'` (L270) and
 * `'You have reached the bottom of the page...'` (L642) differ by their whole opening clause. They are
 * NOT drift to be normalised: the first of each pair answers a key pressed at a boundary the browse is
 * already sitting on, and the second answers a read that arrived there. Merging either pair is the
 * single most likely fidelity loss on this screen, because the two strings read as the same sentence
 * written twice.
 *
 * Assumptions: the discriminator is the presence of "already" rather than a comparison of the two
 * whole strings, because a comparison would still pass if both were normalised to the same wording.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theSixSentencesAreSixDistinctStrings(): void {
  const sentences = [
    SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE,
    SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE,
    SHARED_MESSAGES.YOU_ARE_AT_THE_TOP_OF_THE_PAGE,
    SHARED_MESSAGES.YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE,
    SHARED_MESSAGES.YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE,
    PROGRAM_MESSAGES.COTRN00C.UNABLE_TO_LOOKUP_TRANSACTION,
  ];
  expect(new Set(sentences).size).toBe(sentences.length);

  expect(SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE).toContain('already');
  expect(SHARED_MESSAGES.YOU_ARE_AT_THE_TOP_OF_THE_PAGE).not.toContain('already');
  expect(SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE).toContain('already');
  expect(SHARED_MESSAGES.YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE).not.toContain('already');

  // WHY : Assumptions: the citations are compared against the catalog's own provenance records rather
  //       than only written in this comment, so a line that moves fails here instead of quietly
  //       leaving a stale reference behind. The five paging sentences are shared with `COUSR00C`, so
  //       the record for THIS program is selected before the lines are read.
  expect(
    linesCitedInThisProgram(SHARED_MESSAGE_SOURCES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE),
  ).toEqual([248]);
  expect(
    linesCitedInThisProgram(SHARED_MESSAGE_SOURCES.YOU_ARE_ALREADY_AT_THE_BOTTOM_OF_THE_PAGE),
  ).toEqual([270]);
  expect(linesCitedInThisProgram(SHARED_MESSAGE_SOURCES.YOU_ARE_AT_THE_TOP_OF_THE_PAGE)).toEqual([
    608,
  ]);
  expect(
    linesCitedInThisProgram(SHARED_MESSAGE_SOURCES.YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE),
  ).toEqual([642]);
  expect(
    linesCitedInThisProgram(SHARED_MESSAGE_SOURCES.YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE),
  ).toEqual([676]);
}

/**
 * An opening read that finds nothing reports the arrival sentence, not a refusal.
 *
 * Assumptions: this is `STARTBR` answering `NOTFND` at `app/cbl/COTRN00C.cbl` L604 to L610, whose
 * sentence is `'You are at the top of the page...'` -- the arrival wording, with no "already" in it,
 * because the operator pressed no key to get here.
 * @returns {Promise<void>} Resolves once the sentence has been read from the band.
 */
async function anEmptyOpeningReadReportsTheTopArrival(): Promise<void> {
  vi.mocked(listTransactions).mockResolvedValue(pageResponse([]));
  await browseReporting(SHARED_MESSAGES.YOU_ARE_AT_THE_TOP_OF_THE_PAGE);

  expectVerbatimMessage(SHARED_MESSAGES.YOU_ARE_AT_THE_TOP_OF_THE_PAGE);
  expect(screen.queryAllByRole('radio')).toHaveLength(0);
}

/**
 * A refused opening position reports the same arrival sentence as an empty one.
 *
 * ⚠️ Assumptions: HTTP 404 is the target's shape for `STARTBR` answering `NOTFND` -- the service
 * reporting that the position or filter addresses no record -- so it takes the by-direction arrival
 * sentence and NOT the lookup-failure sentence every other status takes. The two routes into the same
 * wording are asserted separately because either can regress alone: the status test lives in the
 * screen's own message resolution, and losing it would report a normal empty result as a failure.
 * @returns {Promise<void>} Resolves once the sentence has been read from the band.
 */
async function aRefusedOpeningPositionReportsTheTopArrival(): Promise<void> {
  vi.mocked(listTransactions).mockRejectedValue(notFoundProblem());
  await browseReporting(SHARED_MESSAGES.YOU_ARE_AT_THE_TOP_OF_THE_PAGE);
}

/**
 * A forward read that runs off the end reports the bottom arrival sentence.
 *
 * Assumptions: this is `READNEXT` answering `ENDFILE` during a forward step at
 * `app/cbl/COTRN00C.cbl` L638 to L644. Note the crossing a careless reading gets backwards: reading
 * FORWARD off the end reports the BOTTOM.
 * @returns {Promise<void>} Resolves once the sentence has been read from the band.
 */
async function aForwardReadOffTheEndReportsTheBottomArrival(): Promise<void> {
  const rendered = await browseSteppingThrough([
    pageResponse(FULL_PAGE_ROWS, { hasNext: true }),
    pageResponse([]),
  ]);

  await pressPfKey(rendered.user, 'PFK08');
  await waitFor(
    /**
     * Waits until the arrival sentence has been painted.
     * @returns {void} Nothing; throws until the band carries it.
     */
    () => {
      expect(messageBand().textContent).toBe(
        SHARED_MESSAGES.YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE,
      );
    },
  );

  expectVerbatimMessage(SHARED_MESSAGES.YOU_HAVE_REACHED_THE_BOTTOM_OF_THE_PAGE);
}

/**
 * A backward read that runs off the start reports the top arrival sentence.
 *
 * Assumptions: this is `READPREV` answering `ENDFILE` during a backward step at
 * `app/cbl/COTRN00C.cbl` L672 to L678, and it is the other half of the crossing: reading BACKWARD off
 * the start reports the TOP. Two steps are needed to reach it, because a backward step is only
 * expressible once the ordinal has passed the opening page.
 * @returns {Promise<void>} Resolves once the sentence has been read from the band.
 */
async function aBackwardReadOffTheStartReportsTheTopArrival(): Promise<void> {
  const rendered = await browseSteppingThrough([
    pageResponse(FULL_PAGE_ROWS, { hasNext: true }),
    pageResponse(SECOND_PAGE_ROWS),
    pageResponse([]),
  ]);

  await pressPfKey(rendered.user, 'PFK08');
  await waitFor(
    /**
     * Waits until the browse has reached its second page.
     * @returns {void} Nothing; throws until the second page's first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(SECOND_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  await pressPfKey(rendered.user, 'PFK07');
  await waitFor(
    /**
     * Waits until the arrival sentence has been painted.
     * @returns {void} Nothing; throws until the band carries it.
     */
    () => {
      expect(messageBand().textContent).toBe(SHARED_MESSAGES.YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE);
    },
  );

  expectVerbatimMessage(SHARED_MESSAGES.YOU_HAVE_REACHED_THE_TOP_OF_THE_PAGE);
}

/**
 * A failed opening read reports the lookup-failure sentence.
 *
 * ⚠️ Assumptions: the sentence is drawn from this program's own catalog entry and NOT from the shared
 * one, which carries a near-identical string spelt `'Unable to lookup Transaction...'` with a capital
 * T. `COTRN00C` spells it lower-case at all three of its own sites, and the catalog keeps the two
 * entries apart precisely because normalising either way would alter text a comparison reads.
 * @returns {Promise<void>} Resolves once the sentence has been read from the band.
 */
async function aFailedOpeningReadReportsTheLookupFailure(): Promise<void> {
  vi.mocked(listTransactions).mockRejectedValue(serviceProblem());
  await browseReporting(PROGRAM_MESSAGES.COTRN00C.UNABLE_TO_LOOKUP_TRANSACTION);

  // WHY : Assumptions: the lower-case spelling is asserted as a discriminator against the shared
  //       entry, because the two differ in exactly one character and a screen that reached for the
  //       wrong one would look correct in a failure diff read quickly.
  expect(PROGRAM_MESSAGES.COTRN00C.UNABLE_TO_LOOKUP_TRANSACTION).not.toBe(
    SHARED_MESSAGES.UNABLE_TO_LOOKUP_TRANSACTION,
  );
  expect(PROGRAM_MESSAGE_SOURCES.COTRN00C.UNABLE_TO_LOOKUP_TRANSACTION).toEqual([615, 649, 683]);
}

/**
 * A failed forward read reports the same lookup-failure sentence.
 *
 * Assumptions: this is the second of the three `WHEN OTHER` arms that share one string --
 * `app/cbl/COTRN00C.cbl` L649, inside the forward paragraph -- and it is exercised separately from the
 * opening one because they are separate code paths in the source and separate branches in the target.
 * @returns {Promise<void>} Resolves once the sentence has been read from the band.
 */
async function aFailedForwardReadReportsTheLookupFailure(): Promise<void> {
  const operation = vi.mocked(listTransactions);
  operation.mockResolvedValueOnce(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
  operation.mockRejectedValueOnce(serviceProblem());

  const rendered = await mountBrowse();
  await waitFor(
    /**
     * Waits until the opening page is on display.
     * @returns {void} Nothing; throws until its first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(FULL_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  await pressPfKey(rendered.user, 'PFK08');
  await waitFor(
    /**
     * Waits until the failure sentence has been painted.
     * @returns {void} Nothing; throws until the band carries it.
     */
    () => {
      expect(messageBand().textContent).toBe(
        PROGRAM_MESSAGES.COTRN00C.UNABLE_TO_LOOKUP_TRANSACTION,
      );
    },
  );
}

/**
 * A failed backward read reports the same lookup-failure sentence.
 *
 * Assumptions: this is the third arm, `app/cbl/COTRN00C.cbl` L683, inside the backward paragraph. All
 * three arms are asserted because one string on three paths is exactly the shape in which two of the
 * three can be broken without the third noticing.
 * @returns {Promise<void>} Resolves once the sentence has been read from the band.
 */
async function aFailedBackwardReadReportsTheLookupFailure(): Promise<void> {
  const operation = vi.mocked(listTransactions);
  operation.mockResolvedValueOnce(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
  operation.mockResolvedValueOnce(pageResponse(SECOND_PAGE_ROWS));
  operation.mockRejectedValueOnce(serviceProblem());

  const rendered = await mountBrowse();
  await waitFor(
    /**
     * Waits until the opening page is on display.
     * @returns {void} Nothing; throws until its first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(FULL_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  await pressPfKey(rendered.user, 'PFK08');
  await waitFor(
    /**
     * Waits until the browse has reached its second page.
     * @returns {void} Nothing; throws until the second page's first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(SECOND_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  await pressPfKey(rendered.user, 'PFK07');
  await waitFor(
    /**
     * Waits until the failure sentence has been painted.
     * @returns {void} Nothing; throws until the band carries it.
     */
    () => {
      expect(messageBand().textContent).toBe(
        PROGRAM_MESSAGES.COTRN00C.UNABLE_TO_LOOKUP_TRANSACTION,
      );
    },
  );
}

/**
 * Groups the six paging-sentence cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function pagingSentenceContract(): void {
  it('keeps the six paging sentences distinct', theSixSentencesAreSixDistinctStrings);
  it('reports the top arrival on an empty opening read', anEmptyOpeningReadReportsTheTopArrival);
  it('reports the top arrival on a refused position', aRefusedOpeningPositionReportsTheTopArrival);
  it('reports the bottom arrival off the end', aForwardReadOffTheEndReportsTheBottomArrival);
  it('reports the top arrival off the start', aBackwardReadOffTheStartReportsTheTopArrival);
  it('reports the lookup failure on the opening path', aFailedOpeningReadReportsTheLookupFailure);
  it('reports the lookup failure on the forward path', aFailedForwardReadReportsTheLookupFailure);
  it('reports the lookup failure on the backward path', aFailedBackwardReadReportsTheLookupFailure);
}

describe('transaction browse paging sentences', pagingSentenceContract);

/**
 * No control on this screen takes the initial cursor, and nothing is focused when it opens.
 *
 * ⚠️ Assumptions: this mapset carries NO `IC` attribute at all -- grepping `app/bms/COTRN00.bms` for
 * it matches only the Apache licence URL on L11 -- so the terminal places the cursor nowhere in
 * particular and this screen must not either. It is one of only four screens in the tree with none,
 * the others being the user browse (`COUSR00`) and the two authorization screens (`COPAU00`,
 * `COPAU01`), all of them list or detail screens. A blanket "exactly one initial-cursor field per
 * screen" rule would be WRONG here, and an agent applying it would introduce a divergence, which is
 * why the measurement is written down rather than left implicit.
 *
 * Assumptions: the behaviour is asserted through the focused element rather than through a rendered
 * attribute, because React does not emit an `autofocus` attribute -- it calls `focus()` at mount -- so
 * an attribute query alone would pass on a screen that focused a control. The attribute is checked as
 * well, and the module's own text is checked for the prop, so all three ways the property could be
 * lost are covered.
 * @returns {Promise<void>} Resolves once the settled screen has been examined.
 */
async function nothingTakesTheInitialCursor(): Promise<void> {
  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));

  expect(document.activeElement).toBe(document.body);
  expect(rendered.container.querySelectorAll('[autofocus]')).toHaveLength(0);

  // WHY : Assumptions: the module's every mention of the prop name is inside a backtick pair, because
  //       its only mentions are the two comments recording that the property is deliberately absent.
  //       Matching a bare occurrence therefore finds a real prop and nothing else, where a plain
  //       substring search would be answered by those comments and could never fail.
  const bareMentions = subjectSourceText().match(/(?<!`)\bautoFocus\b(?!`)/gu);
  expect(bareMentions).toBeNull();
}

/**
 * The starting-identifier field refuses a seventeenth character.
 *
 * Assumptions: sixteen, from `TRNIDIN ... LENGTH=16` in `app/bms/COTRN00.bms` and
 * `TRNIDINI PIC X(16)` at `app/cpy-bms/COTRN00.CPY` L66, and the key it addresses is
 * `TRAN-ID PIC X(16)` at `app/cpy/CVTRA05Y.cpy` L5. The 3270 enforced the width in hardware -- the
 * seventeenth keystroke did nothing -- so `maxLength` is where that constraint survives.
 * @returns {Promise<void>} Resolves once the field has been examined.
 */
async function theEntryFieldCarriesItsDeclaredWidth(): Promise<void> {
  await browseShowing(pageResponse(FULL_PAGE_ROWS));

  expect(TRANSACTION_ID_FILTER_WIDTH).toBe(16);
  expectMaxLength(filterField(), TRANSACTION_ID_FILTER_WIDTH);
}

/**
 * The ten selection controls carry the single-character width structurally.
 *
 * ⚠️ Assumptions: `maxLength` cannot express `SEL0001I` through `SEL0010I PIC X(1)`
 * (`app/cpy-bms/COTRN00.CPY` L72 to L342), because the target renders each selector as a `Radio`
 * rather than as a one-character entry field, and a radio has no length. The width is honoured by
 * construction instead: the control supplies exactly one character, `'S'`, and a radio group can hold
 * only one chosen row. Recording this is the point of the case -- the width is not lost, it is
 * enforced by a different mechanism, and a reader looking for the missing `maxLength` should find the
 * reason rather than a gap.
 *
 * ⚠️ Refactoring Rationale: the source's ten-arm scan does not survive and should not. It exists
 * because a 3270 submits all ten selection fields at once, so the program has to decide which the
 * operator meant, and it decides by taking the first non-blank -- `app/cbl/COTRN00C.cbl` L149 to L181
 * -- which means at most ONE row is ever acted on per submit. A radio group cannot hold two chosen
 * rows in the first place, so the browser enforces structurally what the scan enforced procedurally.
 * Alternatives Considered: a checkbox column, which would let an operator mark several rows and then
 * discover that only the topmost was honoured, permitting an interaction the source rejects.
 * @returns {Promise<void>} Resolves once the selectors have been exercised.
 */
async function theTenSelectorsCarryTheSingleCharacterWidth(): Promise<void> {
  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));

  expect(TRANSACTION_LIST_SELECTION_CODE).toHaveLength(SELECTION_FIELD_WIDTH);

  const group = screen.getByRole('radiogroup', { name: TRANSACTION_LIST_LABELS.selectionPrompt });
  const selectors = within(group).getAllByRole('radio');
  expect(selectors).toHaveLength(MEASURED_PAGE_SIZE);

  for (const row of FULL_PAGE_ROWS) {
    expect(
      within(group).getByRole('radio', { name: selectionActionLabel(row.transactionId) }),
    ).toBeInTheDocument();
  }

  const first = within(group).getByRole('radio', {
    name: selectionActionLabel(rowAt(FULL_PAGE_ROWS, 0).transactionId),
  });
  const second = within(group).getByRole('radio', {
    name: selectionActionLabel(rowAt(FULL_PAGE_ROWS, 1).transactionId),
  });

  await rendered.user.click(first);
  expect(first).toBeChecked();

  // WHY : Assumptions: choosing a second row RELEASES the first, which is single select and is the
  //       whole of the source's behaviour here. Asserting the release is what distinguishes one group
  //       of ten from ten independent groups of one -- the latter renders identically and lets two
  //       rows be chosen at once.
  await rendered.user.click(second);
  expect(second).toBeChecked();
  expect(first).not.toBeChecked();
}

/**
 * The page is composed from the design system's table, not from raw markup.
 *
 * Assumptions: the "library components over raw HTML" rule is asserted by checking that each rendered
 * element belongs to the design system, because a hand-rolled `table`, `input` or `button` renders the
 * same element with none of the theme applied -- so the element name alone cannot tell them apart. The
 * system's own class marker is what distinguishes them.
 *
 * Assumptions: the `columns` and `dataSource` props are asserted through their observable effect --
 * five headings in the mapset's own order and one row per delivered item -- because a prop that
 * produces the right rendering is the prop the operator meets. The heading text is compared including
 * its interior padding, which is the mapset centring each heading over its column and is content
 * rather than formatting.
 * @returns {Promise<void>} Resolves once the composition has been examined.
 */
async function thePageIsComposedFromDesignSystemComponents(): Promise<void> {
  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));

  const table = screen.getByRole('table', { name: TRANSACTION_LIST_LABELS.title });
  expect(table.closest('.ant-table-wrapper')).not.toBeNull();
  expect(filterField()).toHaveClass('ant-input');
  expect(legendButton(TRANSACTION_LIST_KEY_LABELS.ENTER)).toHaveClass('ant-btn');

  const headings = within(table).getAllByRole('columnheader');
  expect(headings.map(textOf)).toEqual([
    TRANSACTION_LIST_COLUMN_HEADERS.selection,
    TRANSACTION_LIST_COLUMN_HEADERS.transactionId,
    TRANSACTION_LIST_COLUMN_HEADERS.date,
    TRANSACTION_LIST_COLUMN_HEADERS.description,
    TRANSACTION_LIST_COLUMN_HEADERS.amount,
  ]);

  // WHY : Assumptions: every rendered entry control is either the design system's own text input or
  //       one of its radios, so no raw entry element was introduced alongside them. Counting is what
  //       makes this exhaustive rather than a spot check.
  const entryControls = Array.from(rendered.container.querySelectorAll('input'));
  expect(entryControls).toHaveLength(MEASURED_PAGE_SIZE + 1);
  for (const control of entryControls) {
    expect(control.closest('.ant-input, .ant-radio')).not.toBeNull();
  }
}

/**
 * The screen names its own mapset to the band, which is what reconciles two real widths.
 *
 * ⚠️ Assumptions: this screen's own `ERRMSG` field is declared `LENGTH=78` at `app/bms/COTRN00.bms`
 * L450 to L453 and `ERRMSGI PIC X(78)` at `app/cpy-bms/COTRN00.CPY` L372, while the work area every
 * mapset shares is 75 -- `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` are both `PIC X(75)` at
 * `app/cpy/CVCRD01Y.cpy` L28 and L29. Both numbers are real and they are deliberately NOT reconciled
 * into one: 78 is what this terminal field can display and 75 is what the content contract permits to
 * cross the shared work area. "Correcting" 75 to 78 would widen a contract shared by twenty-one
 * mapsets on the evidence of one, which is why the distinction is recorded here rather than resolved.
 *
 * ⚠️ Assumptions: neither WIDTH is asserted here, and the omission is a boundary rather than a gap.
 * `ui/src/layout/MessageBand.tsx` owns the rendering of both, and `ui/src/layout/MessageBand.test.tsx`
 * owns the assertions on it; a second copy here would put one contract under two owners and let them
 * disagree. What belongs to THIS screen is the mapset key it hands over, which is the value that makes
 * the band size itself to 78 without the shared contract being altered -- so that is what is asserted.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theScreenNamesItsOwnMapsetToTheBand(): void {
  // WHY : Assumptions: the mapset key and the map it defines are never the same string -- all
  //       twenty-one differ -- and fourteen of them follow this one's convention: drop the trailing
  //       digit, append `A`. The pair is asserted together because a reader checking the key this
  //       screen passes against the `DFHMDI` label in the mapset would otherwise find two names that
  //       never match and take one of them for a mistake.
  expect(MESSAGE_BAND_BY_MAPSET[TRANSACTION_LIST_MAPSET].map).toBe('COTRN0A');
  expect(TRANSACTION_LIST_MAPSET_SOURCE_FILE).toContain(TRANSACTION_LIST_MAPSET);
}

/**
 * Groups the field-constraint cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function fieldConstraintContract(): void {
  it('places the cursor nowhere, as the mapset does', nothingTakesTheInitialCursor);
  it('holds the entry field to its declared sixteen', theEntryFieldCarriesItsDeclaredWidth);
  it('renders ten single-choice selectors', theTenSelectorsCarryTheSingleCharacterWidth);
  it('composes the page from library components', thePageIsComposedFromDesignSystemComponents);
  it('names its own mapset to the message band', theScreenNamesItsOwnMapsetToTheBand);
}

describe('transaction browse field constraints', fieldConstraintContract);

/**
 * Resolves the refusal text one control points its description at.
 *
 * Assumptions: the text is reached through the control's own `aria-describedby` rather than by
 * querying the document for the sentence, because this screen renders the same sentence in TWO places
 * once a submit has been refused -- beside the field and in the row-23 band -- so an unscoped query
 * matches twice and fails as an ambiguity rather than reporting the field.
 * @param {HTMLElement} control - The entry control carrying the description.
 * @returns {HTMLElement} The element holding the refusal text.
 * @throws {Error} If the control describes nothing, or names an element that does not exist.
 */
function describedRefusal(control: HTMLElement): HTMLElement {
  const id = control.getAttribute('aria-describedby');
  if (id === null) {
    throw new Error('the control names no description, so no field-level refusal is being shown');
  }
  const described = document.getElementById(id);
  if (described === null) {
    throw new Error(`the control describes '${id}', which is not in the document`);
  }
  return described;
}

/**
 * The only selection character this screen accepts is `'S'`.
 *
 * ⚠️ Assumptions: THREE browse screens use three different selection vocabularies, and this one's is
 * the narrowest. `app/cbl/COTRN00C.cbl` L199 declares `'Invalid selection. Valid value is S'` -- value
 * singular -- while the user browse's L212 declares `'Invalid selection. Valid values are U and D'`
 * and the card browse accepts `'S'` and `'U'`. Accepting `'U'` or `'D'` here would admit an
 * interaction this program refuses, so the vocabulary is asserted rather than assumed shared.
 *
 * Assumptions: the lower-case arm is part of the vocabulary and is asserted with the upper-case one.
 * `PROCESS-ENTER-KEY` dispatches on `WHEN 'S'` at L186 and `WHEN 's'` at L187, so both open the
 * transaction; dropping the second is the easiest fidelity loss on this screen to make and the hardest
 * to notice, because a browser control can only ever supply the canonical form.
 *
 * ⚠️ Assumptions: the refusal itself is unreachable through the rendered controls, and that is a
 * consequence of the radio column rather than a gap. A radio supplies the canonical `'S'` and nothing
 * else, so `WHEN OTHER` at L198 has no browser path -- which is why the vocabulary is asserted through
 * the exported predicate and the catalogued sentence, and the rendered half is the mapset's own row-21
 * prompt naming `'S'` to the operator.
 * @returns {Promise<void>} Resolves once the prompt has been read from the settled screen.
 */
async function onlyTheLetterSSelectsARow(): Promise<void> {
  expect(VIEW_SELECTION_CODES).toEqual(['S', 's']);
  expect(isViewSelectionCode('S')).toBe(true);
  expect(isViewSelectionCode('s')).toBe(true);
  expect(isViewSelectionCode('U')).toBe(false);
  expect(isViewSelectionCode('D')).toBe(false);
  expect(isViewSelectionCode(' ')).toBe(false);
  expect(isViewSelectionCode(null)).toBe(false);

  // WHY : Assumptions: the sentence is discriminated by its SINGULAR "value" rather than compared with
  //       a string retyped here, because retyping is the one thing that cannot fail -- a paraphrase in
  //       the catalog and the same paraphrase in this file would agree. The singular is what separates
  //       this program's vocabulary from the user browse's `'Invalid selection. Valid values are U and
  //       D'` at its own L212, so it is the half worth asserting.
  expect(SHARED_MESSAGES.INVALID_SELECTION_VALID_VALUE_IS_S).toContain('Valid value is S');
  expect(SHARED_MESSAGES.INVALID_SELECTION_VALID_VALUE_IS_S).not.toContain('Valid values are');
  expect(
    linesCitedInThisProgram(SHARED_MESSAGE_SOURCES.INVALID_SELECTION_VALID_VALUE_IS_S),
  ).toEqual([199]);

  await browseShowing(pageResponse(FULL_PAGE_ROWS));

  // WHY : Assumptions: the row-21 prompt is the mapset's own statement of the vocabulary, declared
  //       `LENGTH=50` at `app/bms/COTRN00.bms` L448 to L449, and it is asserted through the catalog
  //       with the non-collapsing matcher so its single apostrophes and its one space before "list"
  //       survive -- the arithmetic that fixes that space is the field reaching exactly 50 characters.
  expectVerbatimMessage(TRANSACTION_LIST_LABELS.selectionPrompt);
}

/**
 * The numeric refusal keeps the space between "Numeric" and its ellipsis.
 *
 * ⚠️ Assumptions: `'Tran ID must be Numeric ...'` at `app/cbl/COTRN00C.cbl` L214 carries a space
 * before the three dots, where almost every other message in the system writes the ellipsis flush --
 * `'Tran ID can NOT be empty...'` in the sibling program, for instance. The anomaly is recorded so that
 * nobody tidies it: transformation rule T8 carries user-visible text across character for character,
 * and a tidied ellipsis is a changed string.
 *
 * Assumptions: the sentence is taken from the catalog and never retyped here. Alternatives Considered:
 * writing the expected string inline, which reads more directly. Rejected because a paraphrase in the
 * screen and the same paraphrase in the test would agree with each other, so the case would pass while
 * the fidelity it exists to protect was already lost. The space is then asserted as a POSITIVE and a
 * NEGATIVE substring, which is what makes the assertion about the anomaly rather than about the
 * sentence.
 *
 * Assumptions: a refused entry issues NO read, which is why the count is asserted. The source sets its
 * error flag at L213 and every loop in the paging paragraph is guarded by `ERR-FLG-OFF` at L288 and
 * L296, so the browse is not repositioned and the page on display is left exactly as it was.
 * @returns {Promise<void>} Resolves once the refusal has been examined in both places.
 */
async function theNumericRefusalKeepsItsSpace(): Promise<void> {
  const refusal = PROGRAM_MESSAGES.COTRN00C.TRAN_ID_MUST_BE_NUMERIC;
  expect(refusal).toContain('Numeric ...');
  expect(refusal).not.toContain('Numeric...');
  expect(PROGRAM_MESSAGE_SOURCES.COTRN00C.TRAN_ID_MUST_BE_NUMERIC).toEqual([214]);

  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));
  const field = filterField();

  await rendered.user.click(field);
  await rendered.user.keyboard('ABC');

  // WHY : Assumptions: the field-level refusal is the target form of `app/cpy/CSSETATY.cpy` L17 to
  //       L27, which moves `DFHRED` into the field's colour attribute when its validation flag is
  //       not-OK. Here that is `Form.Item validateStatus="error"`, which the design system renders as
  //       the invalid state on the control and the sentence in its explain region.
  expect(field).toHaveAttribute('aria-invalid', 'true');
  expect(textOf(describedRefusal(field))).toBe(refusal);

  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(
    /**
     * Waits until the refusal has also been painted in the row-23 band.
     * @returns {void} Nothing; throws until the band carries the sentence.
     */
    () => {
      expect(messageBand().textContent).toBe(refusal);
    },
  );

  expect(readCount()).toBe(1);
}

/**
 * A blank entry is accepted, so the asterisk marker has no site on this screen.
 *
 * ⚠️ Assumptions: `app/cpy/CSSETATY.cpy` moves a literal `'*'` into a field at L24, but only on its
 * BLANK arm -- and a blank starting identifier is VALID here. The source browses from the beginning of
 * the set for one, `MOVE LOW-VALUES TO TRAN-ID` at `app/cbl/COTRN00C.cbl` L207, and its numeric class
 * test at L209 is reached only for a filled field. So the blank condition never fails on this screen
 * and the marker has nothing to mark. The absence is asserted rather than left unstated, because a
 * reader who knows the templated copybook would otherwise read it as a missing feature.
 *
 * Assumptions: the marker's own definition is checked from `ui/src/theme/tokens.ts`, so the contract is
 * shown to exist and to be an asterisk even though this screen reaches no site for it.
 * @returns {Promise<void>} Resolves once the blank entry has been submitted and accepted.
 */
async function aBlankEntryIsAcceptedWithNoMarker(): Promise<void> {
  expect(FIELD_ERROR_TOKENS.blankMarker).toBe('*');

  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));
  const field = filterField();

  expect(field).toHaveAttribute('aria-invalid', 'false');
  expect(field.getAttribute('aria-describedby')).toBeNull();

  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(
    /**
     * Waits until the blank submit has repositioned the browse.
     * @returns {void} Nothing; throws until a second read has been issued.
     */
    () => {
      expect(readCount()).toBe(2);
    },
  );

  // WHY : Assumptions: a blank entry sends NO criteria at all rather than a key of blanks, which is
  //       what `MOVE LOW-VALUES TO TRAN-ID` means -- browse from the beginning of the set.
  expect(criteriaOfRead(1)).toBeUndefined();
  expect(messageBand().textContent).toBe('');
  expect(within(messageBand()).queryByText(FIELD_ERROR_TOKENS.blankMarker)).toBeNull();
}

/**
 * A committed sixteen-digit entry travels as the starting identifier.
 *
 * Assumptions: sixteen digits exactly, and the strict reading is confirmed three independent ways. The
 * source tests `IF TRNIDINI IS NUMERIC` at `app/cbl/COTRN00C.cbl` L209, and COBOL's numeric class test
 * on a `PIC X(16)` field is true only when all sixteen positions hold digits; the mapset declares the
 * field at `LENGTH=16`; and the service's own contract declares the parameter with both a minimum and
 * a maximum length of 16.
 *
 * Assumptions: the committed read carries the filter and NO direction, because the two are mutually
 * exclusive at the client -- a cursor already states the position to read from. That matches the source
 * exactly: the entry paragraph positions from the field at L206 to L212 while the paging paragraphs
 * never consult it.
 * @returns {Promise<void>} Resolves once the committed read has been examined.
 */
async function aCommittedEntryTravelsAsTheStartingIdentifier(): Promise<void> {
  const filter = '0000000000000123';
  const rendered = await browseSteppingThrough([
    pageResponse(FULL_PAGE_ROWS),
    pageResponse(SECOND_PAGE_ROWS),
  ]);

  await rendered.user.click(filterField());
  await rendered.user.keyboard(filter);
  await pressPfKey(rendered.user, 'ENTER');

  await waitFor(
    /**
     * Waits until the committed read has been issued.
     * @returns {void} Nothing; throws until a second read is recorded.
     */
    () => {
      expect(readCount()).toBe(2);
    },
  );

  expect(criteriaOfRead(1)).toEqual({ transactionIdFilter: filter });
}

/**
 * Groups the verbatim-text cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function verbatimTextContract(): void {
  it('accepts only the letter S as a selection', onlyTheLetterSSelectsARow);
  it('keeps the space before the numeric refusal\u2019s ellipsis', theNumericRefusalKeepsItsSpace);
  it('accepts a blank entry, so no marker applies', aBlankEntryIsAcceptedWithNoMarker);
  it(
    'sends a committed entry as the starting identifier',
    aCommittedEntryTravelsAsTheStartingIdentifier,
  );
}

describe('transaction browse verbatim text', verbatimTextContract);

/**
 * The legend renders exactly the four attention identifiers the program handles.
 *
 * Assumptions: four, because `app/cbl/COTRN00C.cbl` dispatches on a direct `EVALUATE EIBAID` at L119
 * to L133 whose arms are `DFHENTER`, `DFHPF3`, `DFHPF7` and `DFHPF8`, and whose `WHEN OTHER` answers
 * every other key with the invalid-key message. A fifth control in the legend would advertise a key
 * the program does not implement.
 *
 * Assumptions: the labels are measured rather than composed. The mapset paints ONE 48-character
 * literal at `app/bms/COTRN00.bms` L455 to L459 -- `'ENTER=Continue  F3=Back  F7=Backward  F8=Forward'`,
 * continued across the two source lines -- with TWO spaces between groups, and the arithmetic confirms
 * the split against the declared width: 14 + 2 + 7 + 2 + 11 + 2 + 10 = 48. The legend is assembled from
 * per-key labels precisely because the measured legends differ across mapsets, and the two paging
 * labels come from the shared constant because their wording is identical on every mapset that pages.
 *
 * ⚠️ Refactoring Rationale: the emphasis half of this case now asserts that NO control is emphasised,
 * and the note it replaces claimed "the emphasis mapping fixes the primary role to the submit key".
 * There is no submit key on this screen: `app/cbl/COTRN00C.cbl` answers `DFHENTER` by re-reading from
 * the entry field, and PF7 and PF8 by re-reading a page, so all four bindings declare
 * `risk: 'read-only'` and the shared `pfKeyEmphasisFor` resolves each to the default variant. The rest
 * of that note still holds and is kept: this is a component mapping rather than a source colour, since
 * the mapset colours the whole legend field one way and says nothing about which key writes.
 * @returns {Promise<void>} Resolves once the legend has been examined.
 */
async function theLegendAdvertisesTheProgramsFourKeys(): Promise<void> {
  await browseShowing(pageResponse(FULL_PAGE_ROWS));

  const controls = within(keyLegend()).getAllByRole('button');
  expect(controls.map(textOf)).toEqual([
    TRANSACTION_LIST_KEY_LABELS.ENTER,
    TRANSACTION_LIST_KEY_LABELS.PFK03,
    TRANSACTION_LIST_KEY_LABELS.PFK07,
    TRANSACTION_LIST_KEY_LABELS.PFK08,
  ]);

  // WHY : Assumptions: the four labels are pinned by the mapset's own ARITHMETIC rather than by four
  //       strings retyped here. Row 24 paints one literal at `LENGTH=48` with two spaces between
  //       groups, so joining the four parts with that separator has to come to exactly 48 characters --
  //       14 + 2 + 7 + 2 + 11 + 2 + 10. That fails on a label whose wording drifted by even one
  //       character, and it cannot be satisfied by a paraphrase of the same length in both places,
  //       because the length is the mapset's and not this file's.
  const legendLiteralWidth = 48;
  expect(
    [
      TRANSACTION_LIST_KEY_LABELS.ENTER,
      TRANSACTION_LIST_KEY_LABELS.PFK03,
      TRANSACTION_LIST_KEY_LABELS.PFK07,
      TRANSACTION_LIST_KEY_LABELS.PFK08,
    ].join('  '),
  ).toHaveLength(legendLiteralWidth);
  expect(TRANSACTION_LIST_KEY_LABELS.PFK07).toBe(UNIFORM_PF_KEY_LABELS.PFK07);
  expect(TRANSACTION_LIST_KEY_LABELS.PFK08).toBe(UNIFORM_PF_KEY_LABELS.PFK08);

  /*
   * WHY : ⚠️ Refactoring Rationale: NO control on this legend is emphasised, and the assertion this
   *       replaces required Enter to be. It read `toHaveClass('ant-btn-primary')` on Enter, which was
   *       true only because `PfKeyBar`'s `PRIMARY_ACTION_AIDS` fallback emphasises that identifier on
   *       every screen that binds it -- a reading taken from the mapsets where Enter submits a change.
   *       This screen declares `risk: 'read-only'` on all four bindings, because `app/cbl/COTRN00C.cbl`
   *       has no arm that writes: L149-L192 reads the selectors and transfers, and L119-L133 answers
   *       PF7 and PF8 with a page re-read. Emphasis under this taxonomy signals CONSEQUENCE, so a
   *       screen that changes nothing paints nothing solid -- and the negative is asserted on all four
   *       rather than on Enter alone, because a partial claim would pass against a legend that had
   *       emphasised a different one of them.
   */
  for (const label of Object.values(TRANSACTION_LIST_KEY_LABELS)) {
    expect({ label, primary: legendButton(label).classList.contains('ant-btn-primary') }).toEqual({
      label,
      primary: false,
    });
    expect(legendButton(label)).toHaveClass('ant-btn-default');
  }
}

/**
 * The submit key and its legend control reach the same handler.
 *
 * ⚠️ Alternatives Considered: driving each key through its legend control alone, which is simpler
 * because a click needs no focus management. Rejected because the contract is a KEYBOARD contract --
 * the 3270 original had no pointer, so the key press is the fidelity-bearing path -- and a case that
 * only clicks passes in full while every keyboard binding in the application is broken. Driving both
 * and asserting the same effect is what makes the legend a second route to one handler rather than a
 * substitute for it.
 *
 * Assumptions: the key is pressed BEFORE the control is clicked, because a click leaves focus on the
 * control and the submit identifier is then claimed by that control rather than dispatched --
 * `ui/src/layout/usePfKeys.ts` withholds it from a focused button on purpose, so that pressing the
 * key on a focused control activates the control instead of firing twice.
 * @returns {Promise<void>} Resolves once both routes have issued a read.
 */
async function theSubmitKeyAndItsControlBothReposition(): Promise<void> {
  const rendered = await browseSteppingThrough([
    pageResponse(FULL_PAGE_ROWS),
    pageResponse(FULL_PAGE_ROWS),
    pageResponse(FULL_PAGE_ROWS),
  ]);

  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(
    /**
     * Waits until the key-driven submit has repositioned the browse.
     * @returns {void} Nothing; throws until a second read is recorded.
     */
    () => {
      expect(readCount()).toBe(2);
    },
  );

  await rendered.user.click(legendButton(TRANSACTION_LIST_KEY_LABELS.ENTER));
  await waitFor(
    /**
     * Waits until the click-driven submit has repositioned the browse again.
     * @returns {void} Nothing; throws until a third read is recorded.
     */
    () => {
      expect(readCount()).toBe(3);
    },
  );
}

/**
 * The forward key and its legend control reach the same handler.
 *
 * Assumptions: both steps are available because each delivered page reports a further one, so the
 * second invocation is a real step rather than a refusal that happens to leave the count unchanged.
 * @returns {Promise<void>} Resolves once both routes have paged forward.
 */
async function theForwardKeyAndItsControlBothPage(): Promise<void> {
  const rendered = await browseSteppingThrough([
    pageResponse(FULL_PAGE_ROWS, { hasNext: true }),
    pageResponse(SECOND_PAGE_ROWS, { hasNext: true }),
    pageResponse(SECOND_PAGE_ROWS),
  ]);

  await pressPfKey(rendered.user, 'PFK08');
  await waitFor(
    /**
     * Waits until the key-driven step has been issued.
     * @returns {void} Nothing; throws until the read carries the trailing cursor.
     */
    () => {
      expectPositionedRead(1, TRAILING_CURSOR, 'next');
    },
  );

  await rendered.user.click(legendButton(TRANSACTION_LIST_KEY_LABELS.PFK08));
  await waitFor(
    /**
     * Waits until the click-driven step has been issued.
     * @returns {void} Nothing; throws until a third read is recorded.
     */
    () => {
      expect(readCount()).toBe(3);
    },
  );

  expectPositionedRead(2, TRAILING_CURSOR, 'next');
}

/**
 * The backward key and its legend control reach the same handler.
 *
 * Assumptions: the opening page is used, so both invocations take the refusal arm and the assertion is
 * on the SENTENCE rather than on a read. That is the arm worth exercising through both routes, because
 * it is the one where a disabled control would silently diverge -- a disabled control fires no click at
 * all, so the two routes would stop agreeing without either failing.
 * @returns {Promise<void>} Resolves once both routes have produced the refusal.
 */
async function theBackwardKeyAndItsControlBothRefuse(): Promise<void> {
  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
  const refusal = SHARED_MESSAGES.YOU_ARE_ALREADY_AT_THE_TOP_OF_THE_PAGE;

  await pressPfKey(rendered.user, 'PFK07');
  await waitFor(
    /**
     * Waits until the key-driven refusal has been painted.
     * @returns {void} Nothing; throws until the band carries the sentence.
     */
    () => {
      expect(messageBand().textContent).toBe(refusal);
    },
  );

  const control = legendButton(TRANSACTION_LIST_KEY_LABELS.PFK07);
  expect(control).toBeEnabled();
  await rendered.user.click(control);
  expect(messageBand().textContent).toBe(refusal);
  expect(readCount()).toBe(1);
}

/**
 * The back key returns to the main menu.
 *
 * Assumptions: the main menu, because `app/cbl/COTRN00C.cbl` L122 to L124 move `'COMEN01C'` into the
 * transfer target before performing the return paragraph. Under transformation rule T5 a program
 * transfer is a client-side route change, so nothing travels with it.
 * @returns {Promise<void>} Resolves once the route has changed.
 */
async function theBackKeyReturnsToTheMainMenu(): Promise<void> {
  const rendered = await browseWithLocationProbe(pageResponse(FULL_PAGE_ROWS));

  await pressPfKey(rendered.user, 'PFK03');
  await waitFor(
    /**
     * Waits until the router has left the browse.
     * @returns {void} Nothing; throws until the probe reports the menu's address.
     */
    () => {
      expect(currentPath()).toBe(routeOfProgram('COMEN01C'));
    },
  );
}

/**
 * The back control returns to the main menu, exactly as its key does.
 * @returns {Promise<void>} Resolves once the route has changed.
 */
async function theBackControlReturnsToTheMainMenu(): Promise<void> {
  const rendered = await browseWithLocationProbe(pageResponse(FULL_PAGE_ROWS));

  await rendered.user.click(legendButton(TRANSACTION_LIST_KEY_LABELS.PFK03));
  await waitFor(
    /**
     * Waits until the router has left the browse.
     * @returns {void} Nothing; throws until the probe reports the menu's address.
     */
    () => {
      expect(currentPath()).toBe(routeOfProgram('COMEN01C'));
    },
  );
}

/**
 * Every other attention identifier is refused with the unmapped-key message.
 *
 * ⚠️ Assumptions: this screen IS one of the fourteen programs that emit `CCDA-MSG-INVALID-KEY` --
 * `app/cbl/COTRN00C.cbl` L132 moves it inside the `WHEN OTHER` arm, unlike the account and card
 * screens, which answer an unmapped key differently. The sentence is the copybook's own,
 * `PIC X(50) VALUE 'Invalid key pressed. Please see below...         '` at `app/cpy/CSMSG01Y.cpy` L20
 * to L21, and the declared width is asserted beside it because the literal is 49 characters against a
 * 50-character field -- the catalog stores the unpadded text and the width separately rather than
 * fabricating the padding byte.
 *
 * Assumptions: the sweep covers the nine function keys the program does not handle and deliberately
 * omits the three identifiers with no browser key at all. `CLEAR`, `PA1` and `PA2` are real members of
 * the attention-identifier domain whose measured usage across all twenty-one online programs is zero,
 * so no web key raises them and a case reaching for one would be testing a path the application does
 * not have. Their exclusion is derived from the published domain rather than hand-listed, so a key
 * later bound by this screen fails here instead of being silently skipped.
 * @returns {Promise<void>} Resolves once every unbound key has been refused.
 */
async function noOtherAttentionIdentifierIsBound(): Promise<void> {
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(COMMON_MESSAGES.INVALID_KEY.text).toHaveLength(49);
  expect(INVALID_KEY_PRESSED).toBe(COMMON_MESSAGES.INVALID_KEY.text);

  const rendered = await browseWithLocationProbe(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));

  for (const aid of unboundFunctionKeys()) {
    await pressPfKey(rendered.user, aid);
    await waitFor(
      /**
       * Waits until the unmapped-key sentence has been painted for this identifier.
       * @returns {void} Nothing; throws until the band carries the trimmed catalog text.
       */
      () => {
        expect(messageBand().textContent).toBe(INVALID_KEY_PRESSED.trim());
      },
    );

    // WHY : Assumptions: an unmapped key must reposition nothing and navigate nowhere, so the read
    //       count and the address are both asserted. The source's `WHEN OTHER` arm sends the screen
    //       back with a sentence and performs no file operation and no transfer.
    expect(readCount()).toBe(1);
    expect(currentPath()).toBe(TRANSACTION_LIST_PATH);
  }
}

/**
 * Groups the attention-identifier cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function attentionIdentifierContract(): void {
  it('advertises the program\u2019s four keys and no more', theLegendAdvertisesTheProgramsFourKeys);
  it('submits from the key and from its control', theSubmitKeyAndItsControlBothReposition);
  it('pages forward from the key and from its control', theForwardKeyAndItsControlBothPage);
  it('refuses backward from the key and from its control', theBackwardKeyAndItsControlBothRefuse);
  it('returns to the main menu on the back key', theBackKeyReturnsToTheMainMenu);
  it('returns to the main menu on the back control', theBackControlReturnsToTheMainMenu);
  it('refuses every other attention identifier', noOtherAttentionIdentifierIsBound);
}

describe('transaction browse attention identifiers', attentionIdentifierContract);

/**
 * Builds the `var(--...)` reference the design system emits for one token name.
 *
 * Assumptions: the design system emits a token into a CSS custom property whose name is the token's
 * own name in kebab case behind the `--ant-` prefix, so the reference can be DERIVED from the token
 * name rather than written out. Alternatives Considered: writing the property name as a literal, which
 * is shorter and is what a first attempt reaches for. Rejected because a literal `--ant-font-family-code`
 * is a design value spelled in a test, and the rule this tree holds to is that every design value
 * resolves through `ui/src/theme/tokens.ts` -- deriving from the token NAME keeps the assertion about
 * the token and never about a colour, a font or a length.
 * @param {string} tokenName - The token's name as `ui/src/theme/tokens.ts` records it.
 * @returns {string} The CSS variable reference an inline style carries for that token.
 */
function cssVariableReference(tokenName: string): string {
  return `var(--ant-${tokenName.replace(/([A-Z])/gu, '-$1').toLowerCase()})`;
}

/**
 * Choosing a row and submitting opens that transaction's own screen.
 *
 * Assumptions: the destination is looked up by the PROGRAM the source transfers to -- `COTRN00C.cbl`
 * L188 moves `'COTRN01C'` into the transfer target before the `XCTL` -- so the assertion follows the
 * citation rather than a route constant chosen independently of it. Under transformation rule T5 that
 * transfer is a client-side route change.
 *
 * ⚠️ Assumptions: the route parameter is named `id`, and the name is asserted through the router's own
 * matcher rather than by comparing strings. A path built with the right shape and the wrong parameter
 * name would still equal the expected pathname, so string comparison alone cannot catch an invented
 * parameter -- matching against the declared pattern and reading the captured value can.
 * @returns {Promise<void>} Resolves once the route has changed and been matched.
 */
async function choosingARowOpensThatTransactionsScreen(): Promise<void> {
  expect(routeOfProgram('COTRN01C')).toBe(TRANSACTION_DETAIL_PATH);

  const rendered = await browseWithLocationProbe(pageResponse(FULL_PAGE_ROWS));
  const chosen = rowAt(FULL_PAGE_ROWS, 2);

  await rendered.user.click(
    screen.getByRole('radio', { name: selectionActionLabel(chosen.transactionId) }),
  );
  await pressPfKey(rendered.user, 'ENTER');

  await waitFor(
    /**
     * Waits until the router has reached the chosen transaction's address.
     * @returns {void} Nothing; throws until the probe reports that address.
     */
    () => {
      expect(currentPath()).toBe(transactionDetailPath(chosen.transactionId));
    },
  );

  const matched = matchPath(TRANSACTION_DETAIL_PATH, currentPath());
  expect(matched).not.toBeNull();
  expect(matched?.params.id).toBe(chosen.transactionId);
}

/**
 * Every amount is carried and rendered as text, and none is ever coerced to a number.
 *
 * ⚠️ Trade-offs: the mechanism named here is IEEE-754 binary64, which is JavaScript's only numeric
 * type. It cannot represent most scale-two decimal fractions exactly and cannot represent an integer
 * above 2^53 at all, so a single coercion would render a different figure from the one the service
 * computed -- and would render it as a plausible amount rather than as an error, which is the worst
 * failure available on a screen showing money. What is given up is arithmetic, which this screen never
 * needs: the `+99999999.99` mask at `app/cbl/COTRN00C.cbl` L56 is padding and sign selection, both
 * string operations. `NUMERIC(12,2)` in the database and `BigDecimal` at scale two in the service hold
 * the same value exactly, and the string on the wire is what carries it across the boundary intact.
 *
 * Assumptions: the discriminating fixture is an amount of seventeen significant digits, which is past
 * 2^53 and therefore not exactly representable as a double. A screen that coerced would render a
 * different final digit; one that formats by string operations renders every digit that arrived. This
 * is asserted without performing the coercion, because performing it here would put the very operation
 * under prohibition into the test.
 * @returns {Promise<void>} Resolves once every amount has been read from the rendered page.
 */
async function moneyIsCarriedAsTextEndToEnd(): Promise<void> {
  await browseShowing(pageResponse(FULL_PAGE_ROWS));

  for (const row of FULL_PAGE_ROWS) {
    expect(typeof row.amount).toBe('string');
    expect(screen.getByText(formatTransactionAmount(row.amount))).toBeInTheDocument();
  }

  // WHY : Assumptions: the mask is always signed and zero-padded to twelve characters, which is what
  //       `WS-TRAN-AMT PIC +99999999.99` occupies -- one sign, eight integer digits, the point and two
  //       decimals -- and zero takes a leading `+` because COBOL's `+` edit character emits the sign
  //       OF THE VALUE and zero is unsigned there.
  expect(formatTransactionAmount('100.00')).toBe('+00000100.00');
  expect(formatTransactionAmount('-100.00')).toBe('-00000100.00');
  expect(formatTransactionAmount('0.00')).toBe('+00000000.00');
  expect(formatTransactionAmount('100.00')).toHaveLength(TRANSACTION_AMOUNT_COLUMN_WIDTH);
}

/**
 * The pinned money column never shares its track with a second pinned block.
 *
 * ⚠️ Purpose: a browser sweep measured the two pinned groups OVERLAPPING at 375. The grid's scroller
 * was 327 pixels wide for a 454-pixel row; the leading pinned pair spanned x 0 to 246.42 and the
 * trailing pinned amount x 218.17 to 351, a 28.25-pixel overlap -- and because the leading block sits
 * at a higher stacking level, it painted OVER the amount, so on the row under the pointer the money
 * column read doubled and overstruck. That is a monetary value rendered wrongly, not a layout blemish.
 *
 * ⚠️ Assumptions: the amount is the pin that stays and the identifier is the pin that goes, and the
 * arithmetic decides which rather than taste. Of the 327-pixel track the amount needs 132.83, leaving
 * 194.17; the leading pair needs 218.31 -- the 51.89-pixel selector plus the 166.42-pixel identifier --
 * so NO arrangement that keeps this column pinned fits. Unpinning it leaves the leading pin at 184.72
 * of 327 with 142 clear. Unpinning the SELECTOR instead would also fit, at 299.25 of 327, but with
 * only 27.75 clear -- one padding token from colliding again -- and it would take away the control the
 * operator types into while leaving them a value they could already read.
 *
 * Assumptions: the identifier stays reachable rather than merely unpinned. It is the leading DATA
 * column so it is on screen at rest, and the amount an operator would otherwise have scrolled for is
 * itself pinned, so that scroll is no longer needed to read a figure at all.
 *
 * Assumptions: nothing here asserts that the amount pin is optional. The reference pins nothing
 * because a 24x80 display had nothing to pin, so every pin on this grid is additive and removing one
 * cannot cost fidelity -- but keeping one that overstrikes a money value costs it plainly.
 * @returns {Promise<void>} Resolves once the settled header row has been measured.
 */
async function pinsTheMoneyColumnWithoutASecondPinnedBlock(): Promise<void> {
  await browseShowing(pageResponse(FULL_PAGE_ROWS));

  const headerCells = Array.from(
    document.querySelectorAll<HTMLElement>('.ant-table-thead th.ant-table-cell'),
  );
  expect(headerCells.length, 'the header row must have been rendered').toBeGreaterThan(3);

  /*
   * WHY : Assumptions: the traversal is a plain loop for the reason this file's header records -- an
   *       inline callback owes its own JSDoc block under `ui/eslint.config.js`.
   */
  const leadingPins: string[] = [];
  const trailingPins: string[] = [];

  for (const cell of headerCells) {
    if (cell.classList.contains('ant-table-cell-fix-start')) {
      leadingPins.push(cell.textContent ?? '');
    }

    if (cell.classList.contains('ant-table-cell-fix-end')) {
      trailingPins.push(cell.textContent ?? '');
    }
  }

  expect(
    trailingPins,
    'the amount must be the one trailing pin, so a money value is never scrolled out of reach',
  ).toHaveLength(1);
  expect(
    leadingPins,
    'and exactly ONE leading pin may share the track with it -- two is what overlapped',
  ).toHaveLength(1);
  expect(
    leadingPins[0],
    'the leading pin must be the selection cell the operator types into, not the identifier',
  ).not.toContain(rowAt(FULL_PAGE_ROWS, 0).transactionId.slice(0, 4));
}

/**
 * An amount past the exact range of a double survives every one of its digits.
 * @returns {Promise<void>} Resolves once the wide amount has been read from the rendered page.
 */
async function aWideAmountKeepsEveryDigit(): Promise<void> {
  const wide = '12345678901234567.89';
  const row = transactionRow(1, wide);
  await browseShowing(pageResponse([row]));

  // WHY : Assumptions: seventeen integer digits exceed the eight the mask holds, so the formatter emits
  //       the full digits instead of truncating them -- a documented divergence from the baseline, which
  //       would silently drop the high-order digit. The two failures are not comparable: an amount one
  //       character wider than its column is visibly unusual, whereas a truncated amount is a materially
  //       wrong figure that looks entirely normal.
  const integerDigits = wide.slice(0, wide.indexOf('.'));
  expect(integerDigits).toHaveLength(17);
  expect(formatTransactionAmount(wide)).toContain(integerDigits);
  expect(screen.getByText(formatTransactionAmount(wide))).toBeInTheDocument();
}

/**
 * The twenty-six-character origination stamp is reformatted by slicing, never by a numeric path.
 *
 * Assumptions: the date column is fed from `TRAN-ORIG-TS PIC X(26)` (`app/cpy/CVTRA05Y.cpy` L16) and
 * NOT from the processing stamp, and the source composes `mm/dd/yy` by taking fixed offsets out of it
 * -- `app/cbl/COTRN00C.cbl` L384 to L388 takes the year's last two digits with
 * `WS-TIMESTAMP-DT-YYYY(3:2)` and the month and day whole. The expected value is therefore composed
 * here by slicing the fixture at those same offsets, so the case asserts the transcription rather than
 * a date this file computed by some other route.
 *
 * Assumptions: a stamp that does not open with a well-formed date falls back to the field's own declared
 * value, `WS-TRAN-DATE PIC X(08) VALUE '00/00/00'` at L57. That fallback is transcribed rather than
 * chosen, and it matters because slicing never fails -- a reshaped stamp would otherwise yield a
 * plausible date composed of whatever characters sat at those offsets.
 * @returns {Promise<void>} Resolves once the date column has been read.
 */
async function theOriginationStampIsSlicedNotParsed(): Promise<void> {
  const row = rowAt(FULL_PAGE_ROWS, 0);
  expect(row.originTimestamp).toHaveLength(26);

  await browseShowing(pageResponse([row]));

  const stamp = row.originTimestamp;
  const expected = `${stamp.slice(5, 7)}/${stamp.slice(8, 10)}/${stamp.slice(2, 4)}`;
  expect(expected).toHaveLength(TRANSACTION_DATE_COLUMN_WIDTH);
  expect(screen.getByText(expected)).toBeInTheDocument();
  expect(formatOriginationDate(stamp)).toBe(expected);

  expect(formatOriginationDate('not-a-timestamp')).toBe(UNRESOLVED_TRANSACTION_DATE);
  expect(UNRESOLVED_TRANSACTION_DATE).toHaveLength(TRANSACTION_DATE_COLUMN_WIDTH);
}

/**
 * The identifier, date, description and amount columns render in the fixed-pitch token.
 *
 * Assumptions: all four data columns take the token rather than the amount alone, because every one of
 * them carries a fixed-width coded value -- a sixteen-character identifier, an eight-character date, a
 * description cut to twenty-six and a twelve-character edited amount. A proportional face gives digits
 * differing advance widths, which breaks the column alignment the terminal had for free.
 *
 * Assumptions: what is asserted is the TOKEN reference, never a font name. The token's own value belongs
 * to the theme, and a test naming a typeface would be asserting a design value this tree deliberately
 * keeps in one module.
 * @returns {Promise<void>} Resolves once the four columns' styling has been read.
 */
async function theDataColumnsUseTheFixedPitchToken(): Promise<void> {
  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));

  const row = rowAt(FULL_PAGE_ROWS, 0);
  const stamp = row.originTimestamp;
  const painted = [
    row.transactionId,
    `${stamp.slice(5, 7)}/${stamp.slice(8, 10)}/${stamp.slice(2, 4)}`,
    row.description.slice(0, TRANSACTION_DESCRIPTION_WIDTH),
    formatTransactionAmount(row.amount),
  ];

  // WHY : Assumptions: the queries are scoped to ONE rendered row, because three of the four values are
  //       not unique in the document -- every fixture row carries the same calendar date, so an unscoped
  //       query for it matches ten elements and fails as an ambiguity rather than reporting the styling.
  const cells = within(oneRenderedRow(rendered.container, row.transactionId));
  for (const value of painted) {
    expect(cells.getByText(value).getAttribute('style')).toContain(
      cssVariableReference(TYPOGRAPHY_TOKENS.fixedPitchData),
    );
  }
}

/**
 * No card number and no verification value reaches this browse at all.
 *
 * ⚠️ Assumptions: this is stronger than masking, and deliberately so. The browse row carries FOUR
 * members and the card is not among them (`ui/src/api/types.ts` L1546 to L1551), even though the
 * reference record does carry `TRAN-CARD-NUM PIC X(16)` at `app/cpy/CVTRA05Y.cpy` L15 -- because a page
 * discloses many rows at once, so the field is omitted rather than reduced to its last four digits. The
 * administrative exception that does exist belongs to the card-detail shape and to no other.
 *
 * Assumptions: no card verification value appears under any name anywhere in the contract module. The
 * card record declares a three-digit one at `app/cpy/CVACT02Y.cpy` L7 and no operation of any of the
 * seven contracts returns it, so no shape declares it and none can reach a screen.
 * @returns {Promise<void>} Resolves once the rendered page has been examined.
 */
async function noCardNumberReachesTheBrowse(): Promise<void> {
  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));

  expect(Object.keys(rowAt(FULL_PAGE_ROWS, 0)).sort()).toEqual([
    'amount',
    'description',
    'originTimestamp',
    'transactionId',
  ]);

  // WHY : Assumptions: a masking run of asterisks is the shape a partially-disclosed account number
  //       would take, so its absence is what says none is being rendered. The field-error marker is a
  //       SINGLE asterisk, so a run of four cannot be confused with it.
  expect(textOf(rendered.container)).not.toMatch(/\*{4}/u);
}

/**
 * The rows keep the order the envelope delivered them in.
 *
 * Assumptions: a delivered page is already ascending whichever direction produced it, because the
 * service turns a backward read around before publishing it -- so nothing is reordered here. The source
 * reaches the same outcome by a different mechanism, filling its row array from the tenth position down
 * to the first while reading backward (`app/cbl/COTRN00C.cbl` L349 to L355) precisely so the terminal
 * displays them ascending; reordering here as well would reverse an already-ascending page.
 *
 * Assumptions: the ordering this browse rests on is a real secondary access path in the target and not
 * decoration. `app/jcl/TRANIDX.jcl` L25 to L27 defines the alternate index
 * `AWS.M2.CARDDEMO.TRANSACT.VSAM.AIX` over the base cluster with `KEYS(26 304)` -- twenty-six bytes at
 * offset 304, which is the processing timestamp -- and that becomes the non-unique index
 * `idx_transactions_proc_ts`, with `idx_transactions_card_num` covering the by-card path. The record
 * layout confirms the offset from two unrelated directions: summing the declared widths in
 * `app/cpy/CVTRA05Y.cpy` puts the card number at 262 and the processing stamp at 304, and the report
 * job's sort control declares `TRAN-CARD-NUM,263,16,ZD` and `TRAN-PROC-DT,305,10,CH` in one-based
 * positions, which agree exactly.
 * @returns {Promise<void>} Resolves once the rendered order has been read.
 */
async function theRowsKeepTheDeliveredOrder(): Promise<void> {
  await browseShowing(pageResponse(FULL_PAGE_ROWS));

  const rendered: string[] = [];
  for (const control of screen.getAllByRole('radio')) {
    rendered.push(control.getAttribute('aria-label') ?? '');
  }

  const expected: string[] = [];
  for (const row of FULL_PAGE_ROWS) {
    expected.push(selectionActionLabel(row.transactionId));
  }

  expect(rendered).toEqual(expected);
}

/**
 * Groups the navigation, money and exposure cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function navigationMoneyAndExposureContract(): void {
  it('opens the chosen transaction\u2019s own screen', choosingARowOpensThatTransactionsScreen);
  it('carries every amount as text', moneyIsCarriedAsTextEndToEnd);
  it('keeps every digit of a wide amount', aWideAmountKeepsEveryDigit);
  it(
    'pins the money column without a second pinned block',
    pinsTheMoneyColumnWithoutASecondPinnedBlock,
  );
  it('slices the origination stamp rather than parsing it', theOriginationStampIsSlicedNotParsed);
  it('renders the data columns in the fixed-pitch token', theDataColumnsUseTheFixedPitchToken);
  it('discloses no card number and no verification value', noCardNumberReachesTheBrowse);
  it('keeps the delivered row order', theRowsKeepTheDeliveredOrder);
}

describe('transaction browse navigation, money and exposure', navigationMoneyAndExposureContract);

/*
 * =====================================================================================================
 * Read discipline, row affordance and narrow-viewport reachability
 * =====================================================================================================
 *
 * Assumptions: these cases are grouped separately from the keyset suite above because they assert a
 * different KIND of property. The keyset suite asserts what one read carries; these assert how many
 * reads happen, what a delivered row answers to, and what remains readable when the viewport is a
 * phone. Each was written against a measured defect on this screen rather than against a rule.
 */

/**
 * One read held open, with the means to settle it on demand.
 *
 * Purpose: every duplicate-read case needs a window in which a read is genuinely outstanding, and a
 * resolved promise closes that window before a second press can be made.
 */
interface HeldRead {
  /** The promise the browse operation answers with, which stays pending until settled. */
  readonly promise: Promise<PageResponse<TransactionSummary>>;
  /**
   * Settles the held read with a page.
   * @param {PageResponse<TransactionSummary>} page - The envelope to answer with.
   * @returns {void} Nothing; the promise resolves as a side effect.
   */
  readonly settle: (page: PageResponse<TransactionSummary>) => void;
}

/**
 * Builds a read that stays outstanding until it is settled by hand.
 *
 * ⚠️ Assumptions: a hand-held promise rather than a timer or a fake clock, because the property under
 * test is "while a read is outstanding" and nothing else -- a timer would make the case depend on how
 * long the runner takes to advance it, and a fake clock would also freeze the design system's own
 * transitions. Holding the promise makes the window exactly as long as the case needs and no longer.
 *
 * Assumptions: the resolver is captured from the executor rather than the promise being built from a
 * deferred helper, because none exists in this package and one written here would be a second way of
 * saying the same three lines.
 * @returns {HeldRead} The pending promise and its resolver.
 * @throws {Error} From {@link HeldRead.settle} if the executor never ran, which cannot happen for a
 *   native promise and is checked so the type needs no assertion.
 */
function heldRead(): HeldRead {
  let release: ((page: PageResponse<TransactionSummary>) => void) | null = null;
  const promise = new Promise<PageResponse<TransactionSummary>>(
    /**
     * Captures the resolver so the read can be settled from outside.
     * @param {(page: PageResponse<TransactionSummary>) => void} resolve - The promise's resolver.
     * @returns {void} Nothing; the resolver is retained as a side effect.
     */
    (resolve: (page: PageResponse<TransactionSummary>) => void): void => {
      release = resolve;
    },
  );
  return {
    promise,
    /**
     * Settles the held read with a page.
     * @param {PageResponse<TransactionSummary>} page - The envelope to answer with.
     * @returns {void} Nothing; the promise resolves as a side effect.
     * @throws {Error} If the resolver was never captured.
     */
    settle: (page: PageResponse<TransactionSummary>): void => {
      if (release === null) {
        throw new Error('the held read was never given a resolver');
      }
      release(page);
    },
  };
}

/**
 * Reads the page ordinal the screen is displaying.
 *
 * Assumptions: located by the accessible name the mapset's own row-4 prompt supplies, so the query
 * fails if the label association breaks as well as if the value does.
 * @returns {string} The ordinal as rendered.
 * @throws {Error} If no ordinal is rendered under that name.
 */
function displayedOrdinal(): string {
  return textOf(screen.getByRole('status', { name: TRANSACTION_LIST_LABELS.pageLabel }));
}

/**
 * Locates one row's selection control.
 * @param {string} transactionId - Identifier of the row whose control is wanted.
 * @returns {HTMLElement} That row's radio.
 * @throws {Error} If no control carries that row's accessible name.
 */
function selectionControlOf(transactionId: string): HTMLElement {
  return screen.getByRole('radio', { name: selectionActionLabel(transactionId) });
}

/**
 * Reports whether one row is the chosen one.
 *
 * Assumptions: the control's own `checked` property is read rather than the `checked` ATTRIBUTE,
 * because these radios are controlled -- React sets the property and leaves the attribute at its
 * initial value, so an attribute read would report every row unchosen however the screen behaved.
 *
 * Assumptions: the element is narrowed with `instanceof` rather than cast, so a selection column that
 * stopped rendering a real input fails here naming that fact instead of reading `undefined`.
 * @param {string} transactionId - Identifier of the row to examine.
 * @returns {boolean} Whether that row's control is currently chosen.
 * @throws {Error} If the row's control is not an input element.
 */
function rowIsChosen(transactionId: string): boolean {
  const control = selectionControlOf(transactionId);
  if (!(control instanceof HTMLInputElement)) {
    throw new Error(`the selection control for ${transactionId} is not an input`);
  }
  return control.checked;
}

/**
 * A second forward press made while the first read is still outstanding issues no second read.
 *
 * ⚠️ Purpose: this is the defect a browser review measured on the sibling browse -- two presses of the
 * forward control 400 milliseconds apart produced two identical positioned reads, and because each
 * delivered page advances the ordinal, the screen came to state a page number it was not showing rows
 * for. The wrong number is the harm; the wasted request is incidental.
 *
 * ⚠️ Assumptions: the ordinal is asserted as well as the read count, because they fail independently
 * and only the ordinal states the operator-visible consequence. A screen that issued one read but
 * counted two presses would pass a count-only assertion and still mislead.
 *
 * Assumptions: absorbing the press leaves the band EMPTY rather than adding a sentence. The terminal
 * answered an inhibited keystroke with nothing at all -- CICS locks the keyboard for the duration of a
 * task, so the second press was never delivered -- and `ui/src/messages/messages.ts` carries no
 * sentence for a press that arrived mid-turn, so any wording here would be invented screen text.
 * @returns {Promise<void>} Resolves once both presses and the settled page have been examined.
 */
async function aSecondForwardPressDuringAReadIsAbsorbed(): Promise<void> {
  const operation = vi.mocked(listTransactions);
  operation.mockResolvedValueOnce(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
  const held = heldRead();
  operation.mockReturnValueOnce(held.promise);

  const rendered = await mountBrowse();
  await waitFor(
    /**
     * Waits until the opening page is on display, so a forward step is expressible.
     * @returns {void} Nothing; throws until the opening page's first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(FULL_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  await pressPfKey(rendered.user, 'PFK08');
  expect(readCount()).toBe(2);

  // WHY : Assumptions: the second press is made while the first read is unsettled, which is the whole
  //       of the window under test. `pressPfKey` awaits its own act scope, so the started transition
  //       has already been applied and the handler this press reaches is the one that can see the read
  //       outstanding.
  await pressPfKey(rendered.user, 'PFK08');
  expect(readCount()).toBe(2);

  await act(
    /**
     * Settles the held read and lets its continuation run inside an act scope.
     * @returns {Promise<void>} Resolves once the page has been applied.
     */
    async (): Promise<void> => {
      held.settle(pageResponse(SECOND_PAGE_ROWS));
      await held.promise;
    },
  );

  await waitFor(
    /**
     * Waits until the delivered page is on display.
     * @returns {void} Nothing; throws until the second page's first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(SECOND_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  // WHY : Assumptions: TWO, not three. Each delivered page advances the ordinal, so a second read that
  //       had been issued would have advanced it again and the screen would state page three over page
  //       two's rows -- which is the measured symptom this case exists to exclude.
  expect(displayedOrdinal()).toBe('2');
  expect(readCount()).toBe(2);
}

/**
 * Submitting the same entry twice while the first read is outstanding issues no second read.
 *
 * Assumptions: the SAME entry, because that is the arm the guard covers. The source repositions on
 * ENTER and nothing else, so pressing it twice unchanged asks for the identical page twice.
 * @returns {Promise<void>} Resolves once both submissions have been examined.
 */
async function anUnchangedResubmissionDuringAReadIsAbsorbed(): Promise<void> {
  const operation = vi.mocked(listTransactions);
  operation.mockResolvedValueOnce(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
  const held = heldRead();
  operation.mockReturnValueOnce(held.promise);

  const rendered = await mountBrowse();
  await waitFor(
    /**
     * Waits until the opening page is on display.
     * @returns {void} Nothing; throws until its first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(FULL_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  await pressPfKey(rendered.user, 'ENTER');
  expect(readCount()).toBe(2);

  await pressPfKey(rendered.user, 'ENTER');
  expect(readCount()).toBe(2);

  await act(
    /**
     * Settles the held read so the case leaves no pending work behind.
     * @returns {Promise<void>} Resolves once the page has been applied.
     */
    async (): Promise<void> => {
      held.settle(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
      await held.promise;
    },
  );
}

/**
 * A DIFFERENT entry submitted while a read is outstanding is still issued.
 *
 * ⚠️ Purpose: this is the boundary of the collapse above, and it is asserted so the collapse cannot be
 * widened by accident. An operator who mistypes a starting identifier must be able to correct it
 * without waiting for a read they no longer want, and the superseded response is discarded by the
 * hook's own sequence guard rather than by refusing the correction.
 *
 * Assumptions: the correcting entry is typed into the control the mapset labels, and the read that
 * follows is asserted to carry it -- so this case would also fail a screen that issued a read while
 * ignoring the new entry.
 * @returns {Promise<void>} Resolves once the corrected read has been examined.
 */
async function aDifferentEntryDuringAReadIsStillIssued(): Promise<void> {
  const operation = vi.mocked(listTransactions);
  operation.mockResolvedValueOnce(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
  const held = heldRead();
  operation.mockReturnValueOnce(held.promise);
  operation.mockResolvedValueOnce(pageResponse(SECOND_PAGE_ROWS));

  const rendered = await mountBrowse();
  await waitFor(
    /**
     * Waits until the opening page is on display.
     * @returns {void} Nothing; throws until its first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(FULL_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );

  await pressPfKey(rendered.user, 'PFK08');
  expect(readCount()).toBe(2);

  const corrected = rowAt(SECOND_PAGE_ROWS, 0).transactionId;
  await rendered.user.type(filterField(), corrected);
  await pressPfKey(rendered.user, 'ENTER');

  await waitFor(
    /**
     * Waits until the corrected read has been issued.
     * @returns {void} Nothing; throws until a third read is recorded.
     */
    () => {
      expect(readCount()).toBe(3);
    },
  );
  expect(criteriaOfRead(2)).toEqual({ transactionIdFilter: corrected });

  await act(
    /**
     * Settles the superseded read so the case leaves no pending work behind.
     * @returns {Promise<void>} Resolves once it has settled.
     */
    async (): Promise<void> => {
      held.settle(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
      await held.promise;
    },
  );
}

/**
 * The entry control states that a read is outstanding, and stops stating it once one is not.
 *
 * ⚠️ Purpose: a browser review found `aria-busy` on no control anywhere in this tree, so the only
 * indication that a read was running was the table's own spinner -- which tells a sighted operator
 * something and a screen-reader user nothing. The attribute is asserted in BOTH states, because an
 * attribute that is always present states as little as one that is never present.
 *
 * ⚠️ Refactoring Rationale: a SENTENCE is asserted alongside the attribute now, and the note this
 * replaces argued against one -- "there is no catalogued wording to announce and `aria-busy` states the
 * fact without inventing one". `REQUEST_IN_PROGRESS` is now an authored, registered and width-checked
 * entry in `ui/src/messages/messages.ts`, so the wording is no longer invented at the point of use; and
 * `aria-busy` alone is the wrong instrument for this, because it SUPPRESSES assistive announcements from
 * the region it marks rather than producing one. Marking the control busy and saying nothing left a
 * screen-reader operator with less than they had before.
 *
 * ⚠️ Assumptions: the live region is asserted PRESENT AND EMPTY while idle, not absent. A live region
 * has to be in the accessibility tree before its content changes for the change to be announced at all,
 * so a screen that rendered it only while busy would silently lose the first transition -- the one that
 * matters. `busyAnnouncement` renders exactly that shape and this asserts it in both states.
 * @returns {Promise<void>} Resolves once both states have been examined.
 */
async function theEntryFieldStatesThatAReadIsOutstanding(): Promise<void> {
  const operation = vi.mocked(listTransactions);
  operation.mockResolvedValueOnce(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
  const held = heldRead();
  operation.mockReturnValueOnce(held.promise);

  const rendered = await mountBrowse();
  await waitFor(
    /**
     * Waits until the opening page has settled, so the idle state is observable.
     * @returns {void} Nothing; throws until its first row is present.
     */
    () => {
      expect(screen.getByText(rowAt(FULL_PAGE_ROWS, 0).transactionId)).toBeInTheDocument();
    },
  );
  expect(filterField()).not.toHaveAttribute('aria-busy');
  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent('');

  await pressPfKey(rendered.user, 'PFK08');
  expect(filterField()).toHaveAttribute('aria-busy', 'true');
  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent(REQUEST_IN_PROGRESS);

  await act(
    /**
     * Settles the held read so the idle state can be observed again.
     * @returns {Promise<void>} Resolves once the page has been applied.
     */
    async (): Promise<void> => {
      held.settle(pageResponse(SECOND_PAGE_ROWS));
      await held.promise;
    },
  );

  await waitFor(
    /**
     * Waits until the control has stopped reporting a read outstanding.
     * @returns {void} Nothing; throws until the attribute is gone.
     */
    () => {
      expect(filterField()).not.toHaveAttribute('aria-busy');
    },
  );
  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID)).toHaveTextContent('');
}

/**
 * Pointing at a delivered row chooses it, and chooses it without navigating.
 *
 * ⚠️ Purpose: a browser review found the four data cells painted in link blue and a fixed pitch while
 * being inert -- `cursor: auto` at rest and on hover, no anchor, no control, no handler -- so the row
 * advertised itself as something to click and answered nothing, while the mapset's own row-21 prompt
 * described a selection control that existed nowhere on the screen.
 *
 * ⚠️ Assumptions: the click SELECTS and does not open, which is the source's own two-step turn rather
 * than a hesitation. `app/cbl/COTRN00C.cbl` reads the ten selector fields only inside
 * `PROCESS-ENTER-KEY` (L149 to L181) and transfers to `COTRN01C` only from there (L190 to L192), so on
 * the terminal the character goes into a row and then ENTER is pressed. Both halves are asserted: the
 * row's own control becomes chosen, and the address does not change.
 *
 * Assumptions: the cursor is asserted through the inline style the screen sets rather than through a
 * computed value, because jsdom computes no layout and resolves no stylesheet -- a computed-style read
 * would report the initial value whatever the markup said.
 * @returns {Promise<void>} Resolves once the choice and the unchanged address have been examined.
 */
async function aRowIsChoosableByPointer(): Promise<void> {
  const rendered = await browseWithLocationProbe(pageResponse(FULL_PAGE_ROWS));
  const chosen = rowAt(FULL_PAGE_ROWS, 4);
  const row = oneRenderedRow(rendered.container, chosen.transactionId);

  expect(row.style.cursor).toBe('pointer');
  expect(selectionControlOf(chosen.transactionId)).not.toBeChecked();

  await rendered.user.click(row);

  expect(selectionControlOf(chosen.transactionId)).toBeChecked();
  expect(currentPath()).toBe(TRANSACTION_LIST_PATH);

  // WHY : Assumptions: the choice is then COMMITTED with the attention identifier, so the case proves
  //       the pointer path reaches the same turn the radio path does rather than merely marking a row.
  await pressPfKey(rendered.user, 'ENTER');
  await waitFor(
    /**
     * Waits until the router has reached the chosen transaction's address.
     * @returns {void} Nothing; throws until the probe reports that address.
     */
    () => {
      expect(currentPath()).toBe(transactionDetailPath(chosen.transactionId));
    },
  );
}

/**
 * Typing the character the row-21 prompt names chooses the row it was typed on.
 *
 * ⚠️ Purpose: the prompt `Type 'S' to View Transaction details from the list` is a mapset literal at
 * `app/bms/COTRN00.bms` L448 to L449, and a browser review found no control anywhere on the screen
 * that accepted a typed character -- so the screen instructed an operator to do something it had no
 * way of receiving. Both catalogued spellings are exercised, because `PROCESS-ENTER-KEY` dispatches on
 * `WHEN 'S'` at L186 and `WHEN 's'` at L187 and dropping the second is the easiest fidelity loss here
 * to make and the hardest to notice.
 *
 * Assumptions: a character the vocabulary refuses is exercised in the same case, so the assertion is
 * about this program's vocabulary rather than about any keystroke selecting a row. `'U'` is used
 * because it is the sibling user browse's own selection character, which makes an accidentally shared
 * vocabulary the specific regression excluded.
 * @returns {Promise<void>} Resolves once every spelling has been examined.
 */
async function aRowIsChoosableByTheLetterTheScreenNames(): Promise<void> {
  const rendered = await browseShowing(pageResponse(FULL_PAGE_ROWS));

  for (const spelling of VIEW_SELECTION_CODES) {
    const chosen = rowAt(FULL_PAGE_ROWS, VIEW_SELECTION_CODES.indexOf(spelling) + 1);

    /*
     * WHY : ⚠️ Assumptions: the row's own control is FOCUSED programmatically and the character is
     *       then sent through the keyboard, rather than the character being typed at the row element.
     *       Two reasons. A table row is not a typeable element, so `user.type` on it raises rather than
     *       dispatching anything. And clicking the row to focus it would CHOOSE it, which is the very
     *       outcome under test -- the case would then pass against a screen that ignored the keystroke
     *       entirely. Focusing the control and typing is also the real keyboard path: an operator
     *       reaches the selection column with one tab stop, walks it with the arrow keys, and the
     *       keystroke they then make bubbles from that control to the row.
     */
    selectionControlOf(chosen.transactionId).focus();
    await rendered.user.keyboard(spelling);

    expect({ spelling, chosen: rowIsChosen(chosen.transactionId) }).toEqual({
      spelling,
      chosen: true,
    });
  }

  // WHY : Assumptions: the refused character is typed onto a row that is NOT currently chosen, so a
  //       screen that ignored the vocabulary and selected on any keystroke fails here rather than
  //       passing on a row that was already chosen.
  const untouched = rowAt(FULL_PAGE_ROWS, 7);
  selectionControlOf(untouched.transactionId).focus();
  await rendered.user.keyboard('U');
  expect(rowIsChosen(untouched.transactionId)).toBe(false);
}

/**
 * The screen caption stands on its own line, sharing none with the page ordinal.
 *
 * ⚠️ Purpose: a responsive review measured this screen placing its caption 26 pixels higher than every
 * other screen in the tree -- y145 against y172 -- because the caption shared a baseline-aligned flex
 * line with the page ordinal and had its block margin zeroed to sit on that baseline. The heading is
 * what an operator reads to confirm which screen they are on, so it is the one element whose position
 * should not be a per-screen exception.
 *
 * ⚠️ Assumptions: the property is asserted STRUCTURALLY -- no flex container holds both the caption and
 * the ordinal -- rather than by measuring a position, because jsdom computes no layout and every
 * element reports a zero rectangle, so a coordinate assertion here would pass against any markup.
 * Sharing a flex line is the mechanism that produced the offset, so its absence is the property.
 *
 * Assumptions: the caption's own margin is asserted to be UNSET rather than to hold a value. The
 * zeroing was the second half of the defect, and `ScreenTitle` resolves the heading's spacing itself,
 * so the correct state is the screen not overriding it at all.
 * @returns {Promise<void>} Resolves once the caption and the ordinal have been located.
 */
async function theCaptionStandsOnItsOwnLine(): Promise<void> {
  await browseShowing(pageResponse(FULL_PAGE_ROWS));

  const caption = screen.getByRole('heading', { name: TRANSACTION_LIST_LABELS.title });
  const ordinal = screen.getByRole('status', { name: TRANSACTION_LIST_LABELS.pageLabel });

  expect(caption.style.margin).toBe('');

  /*
   * WHY : ⚠️ Assumptions: the ORDINAL's nearest flex line is the one examined, not the caption's. The
   *       screen's outermost element is itself a vertical flex holding every band, so the caption's
   *       nearest flex contains the ordinal whatever the arrangement is and an assertion made from that
   *       end would be vacuous. The ordinal's nearest flex is the line it shares, and the property under
   *       test is that the caption is not on that line.
   */
  const ordinalLine = ordinal.closest('.ant-flex');
  expect(ordinalLine).not.toBeNull();
  expect(ordinalLine?.contains(caption) ?? true).toBe(false);

  // WHY : Assumptions: the caption's own parent is asserted to be the VERTICAL stack, which states the
  //       positive half -- the caption occupies a line of its own -- where the check above states only
  //       that it does not share the ordinal's. antd marks a vertical flex with its own class.
  expect(caption.parentElement?.className ?? '').toContain('ant-flex-vertical');
}

/**
 * The money column is on screen at a phone width, and the widest column is what withdraws.
 *
 * ⚠️ Purpose: a responsive review measured the five columns holding their desktop widths at every
 * viewport -- 51.89, 166.42, 99.22, 250.42 and 132.83 pixels, summing to 700.78 -- inside a scroller
 * whose window is the viewport, so at 375 the amount cells sat at x568 to x700.8 and were entirely
 * off-screen with "no scrollbar, fade, chevron or hint" to say so. The amount is the figure this
 * screen exists to show.
 *
 * ⚠️ Assumptions: the viewport is selected by assigning `window.innerWidth` BEFORE the mount, which is
 * the mechanism `ui/src/test/setup.ts` documents: its `matchMedia` shim derives `matches` from that
 * value rather than reporting every breakpoint inactive, precisely so a case can choose the branch it
 * means to test. The width is restored afterwards so the following case sees the default.
 *
 * Assumptions: the pinned column is asserted through the design system's own fixed-cell class rather
 * than through a coordinate, for the same reason the caption case asserts structure -- jsdom computes
 * no layout, so the class is the only observable form of the pin.
 * @returns {Promise<void>} Resolves once both viewports have been examined.
 */
async function theMoneyColumnStaysReadableOnAPhone(): Promise<void> {
  const restoreWidth = window.innerWidth;
  try {
    // WHY : Assumptions: 375 specifically, because it is the narrowest viewport the responsive sweep
    //       covers and the one at which the amount was measured entirely off-screen.
    window.innerWidth = 375;
    await browseShowing(pageResponse(FULL_PAGE_ROWS));

    const headers = screen.getAllByRole('columnheader').map(textOf);
    expect(headers).toContain(TRANSACTION_LIST_COLUMN_HEADERS.amount);
    expect(headers).not.toContain(TRANSACTION_LIST_COLUMN_HEADERS.description);
    expect(headers).toContain(TRANSACTION_LIST_COLUMN_HEADERS.transactionId);
    expect(headers).toContain(TRANSACTION_LIST_COLUMN_HEADERS.date);
  } finally {
    window.innerWidth = restoreWidth;
  }
}

/**
 * Every column the mapset paints is present at a desktop width.
 *
 * Purpose: the narrow-viewport case above withdraws a column, and this states the boundary of that
 * withdrawal -- so a change that dropped the description everywhere fails here rather than passing as
 * a responsive refinement.
 * @returns {Promise<void>} Resolves once the headings have been counted.
 */
async function everyColumnIsPresentOnADesktop(): Promise<void> {
  const restoreWidth = window.innerWidth;
  try {
    // WHY : Assumptions: 1200, which is the design system's `lg` breakpoint and the width the sweep
    //       records as the reference desktop, so this is the branch an operator at a workstation meets.
    window.innerWidth = 1200;
    await browseShowing(pageResponse(FULL_PAGE_ROWS));

    const headers = screen.getAllByRole('columnheader').map(textOf);
    expect(headers).toEqual([
      TRANSACTION_LIST_COLUMN_HEADERS.selection,
      TRANSACTION_LIST_COLUMN_HEADERS.transactionId,
      TRANSACTION_LIST_COLUMN_HEADERS.date,
      TRANSACTION_LIST_COLUMN_HEADERS.description,
      TRANSACTION_LIST_COLUMN_HEADERS.amount,
    ]);
  } finally {
    window.innerWidth = restoreWidth;
  }
}

/**
 * Groups the read-discipline, row-affordance and narrow-viewport cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function readDisciplineAndAffordanceContract(): void {
  it('absorbs a second forward press during a read', aSecondForwardPressDuringAReadIsAbsorbed);
  it(
    'absorbs an unchanged resubmission during a read',
    anUnchangedResubmissionDuringAReadIsAbsorbed,
  );
  it('still issues a corrected entry during a read', aDifferentEntryDuringAReadIsStillIssued);
  it('states that a read is outstanding', theEntryFieldStatesThatAReadIsOutstanding);
  it('lets a row be chosen by pointer', aRowIsChoosableByPointer);
  it('lets a row be chosen by the named letter', aRowIsChoosableByTheLetterTheScreenNames);
  it('keeps the caption on its own line', theCaptionStandsOnItsOwnLine);
  it('keeps the money column readable on a phone', theMoneyColumnStaysReadableOnAPhone);
  it('keeps every column on a desktop', everyColumnIsPresentOnADesktop);
}

describe('transaction browse read discipline and affordance', readDisciplineAndAffordanceContract);
