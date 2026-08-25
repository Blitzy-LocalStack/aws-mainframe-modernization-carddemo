/**
 * @file Component tests for the card-list browse screen, the migration target of BMS mapset
 * `COCRDLI` / map `CCRDLIA` and program `app/cbl/COCRDLIC.cbl`, mounted at `/cards`.
 *
 * Purpose
 * -------
 * This screen is the reference case for the whole keyset-pagination design, because its baseline
 * browse state ALREADY WAS a keyset cursor rather than an offset: `app/cbl/COCRDLIC.cbl` L230-L244
 * persists a last-key pair, a first-key pair, a screen ordinal, a last-page-displayed flag and a
 * next-page indicator across the pseudo-conversational gap, and L1284-L1285 sets that indicator by
 * reading one more record than the screen has room for. The cases below fix that mapping one to one,
 * together with the field widths the mapset declares, the four attention identifiers the program
 * dispatches, the sentences it paints character for character, and the two data-exposure properties
 * the browse owes every row it renders.
 *
 * ⚠️ This file is the ONLY verification this screen gets, and that is a property of the baseline
 * rather than a gap in the suite. `tests/README.md` §1.1 states it in its own words: "Online `CO*`
 * CICS programs cannot run end-to-end without a CICS runtime (absent on the runner); only their
 * extractable field-validation logic is unit-tested." The COBOL suite is the functional-parity oracle
 * for the BATCH chain only -- `tests/golden/` holds masters for its five batch domains, `posting`,
 * `interest`, `statement`, `reporting` and `provisioning`, and NONE for any online program including
 * `COCRDLIC`. So there is no golden master to compare against here, and
 * no assertion below claims one: each one is anchored to a cited line of the immutable baseline
 * instead, which is the strongest oracle this screen has.
 *
 * Parameters (module analogue)
 * ----------------------------
 * None. A test module is not invoked with arguments. Its inputs are the transport module it replaces
 * with `vi.mock`, the page envelopes it builds through `pageResponse` from `./setup`, and the
 * keyboard and pointer events its cases dispatch.
 *
 * Returns (module analogue)
 * -------------------------
 * Nothing. The registered cases carry the outcome; Vitest reports it.
 *
 * Exceptions (module analogue)
 * ----------------------------
 * A failing expectation throws, which Vitest reports as a failed case. `pressPfKey` throws for an
 * attention identifier with no browser key -- `CLEAR`, `PA1` and `PA2` -- and no case below reaches
 * for one, because this program dispatches none of the three.
 *
 * Documentation obligation
 * ------------------------
 * Two documents impose the same obligation on this file and they AGREE, so nothing here trades one
 * against the other. Rule 1 (Explainability) requires a docstring on every function and module entry
 * point giving purpose, parameters, returns and exceptions, and an inline comment justifying every
 * non-obvious decision under one of four named categories. `tests/README.md` §12 imposes exactly that
 * on "every new test, fixture builder, helper, mock, and runner routine" and calls it "a hard review
 * gate". This file therefore EXTENDS an established house convention rather than introducing one, and
 * follows `docs/CODE_DOCUMENTATION_STANDARD.md` for the form: the four canonical labels in their
 * plural spelling, unparenthesised and unemphasised, and no statement-level `WHAT:` comment outside
 * this header block, both of which `config/rule1/rule1_gate.py` enforces mechanically.
 *
 * Assumptions: every test API is imported by name rather than taken from an ambient global.
 * `ui/tsconfig.json` keeps `types` EMPTY, so nothing is declared ambiently and an omitted import
 * fails to compile on the symbol it omitted. `ui/vitest.config.ts` sets `globals: true` for the
 * separate reason recorded there -- an injected global is not a declared one, so the import is what
 * satisfies the compiler, and it also keeps this file's dependencies visible to the lint rules that
 * govern the rest of the tree.
 *
 * Assumptions: every function passed to `vi.mock`, `describe`, `it` and `waitFor` is a NAMED
 * declaration rather than an inline arrow. `ui/eslint.config.js` sets `jsdoc/require-jsdoc` with
 * `publicOnly: false` and a `* > ArrowFunctionExpression` context, so an inline callback owes its own
 * JSDoc block in every position; Prettier then moves a block comment that follows an argument comma
 * onto the preceding string literal, detaching the block from the function it documents. Naming the
 * function is what keeps the two together. `ui/src/screens/cardList/browseNarrowing.test.tsx` records
 * the same finding.
 */

import { ConfigProvider } from 'antd';
import { MemoryRouter, Route, Routes } from 'react-router';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { act, getDefaultNormalizer, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { getCard, listCards } from '../api/cards';
import { ApiRequestError } from '../api/client';
import type { CardListQuery, CardSummary, PageDirection, PageResponse } from '../api/types';
import {
  INFORMATION_BAND_TEST_ID,
  MESSAGE_BAND_CONTENT_WIDTH,
  MESSAGE_BAND_TEST_ID,
} from '../layout/MessageBand';
import { BUSY_ANNOUNCEMENT_TEST_ID } from '../layout/fieldHelp';
import { PF_KEY_BAR_REGION_LABEL, UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import {
  PERSISTENT_FAILURE_REPORT_IT,
  PROGRAM_MESSAGES,
  REQUEST_IN_PROGRESS,
  SHARED_MESSAGES,
  STATUS_MESSAGES,
  TRANSIENT_FAILURE_TRY_AGAIN,
} from '../messages/messages';
import { PROGRAM_MESSAGE_SOURCES, SHARED_MESSAGE_SOURCES } from '../messages/messages';
import {
  CARD_EDIT_ROUTE,
  CARD_SELECTOR_LENGTH,
  cardDetailPath,
  cardEditPath,
} from '../routes/cards';
import {
  CARD_LIST_ACCOUNT_FILTER_WIDTH,
  CARD_LIST_CARD_FILTER_WIDTH,
  CARD_LIST_ENTRY_CONTROL_LABELS,
  CARD_LIST_LABELS,
  CARD_LIST_PAGE_SIZE,
  CARD_LIST_ROW_ACTION_CODES,
  reduceCardListSelection,
} from '../screens/cardList/index';
import { AppShell } from '../layout/AppShell';
import CardListScreen from '../screens/cardList/index';
import { CardUpdateScreen } from '../screens/cardUpdate/index';
import { cardDemoTheme } from '../theme/antdTheme';
import { TYPOGRAPHY_TOKENS } from '../theme/tokens';
import { LEADING_CURSOR, TRAILING_CURSOR, pageResponse, pressPfKey } from './setup';
import { apiError, expectMaxLength, expectVerbatimMessage, renderInAppShell } from './setup';

/**
 * Builds the mocked surface of the card transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts
 * every `vi.mock` call above the imports and a factory held in a `const` would still be in its
 * temporal dead zone when the registration runs.
 *
 * Assumptions: all four published operations are replaced even though only `listCards` is exercised
 * here. `vi.mock` replaces the whole module, so an operation left undeclared would be `undefined`
 * rather than absent, and a screen reaching it would fail on a missing function instead of on the
 * behaviour under test.
 * @returns {Record<string, unknown>} The four card operations, each an independent spy.
 */
function mockCardTransportModule(): Record<string, unknown> {
  return {
    listCards: vi.fn(),
    lookupCard: vi.fn(),
    getCard: vi.fn(),
    updateCard: vi.fn(),
  };
}

/*
 * WHY : Assumptions: the transport MODULE is mocked rather than the HTTP client beneath it, because
 *       every case here asserts what the screen SENDS and which sentence it paints -- properties of
 *       the screen, not of the interceptor chain. No request-interception library is installed in
 *       this package, so no request ever leaves the process, and no endpoint, host name or credential
 *       appears anywhere in this file.
 */
vi.mock('../api/cards', mockCardTransportModule);

/**
 * How long a wait for an asynchronous condition is given, in milliseconds.
 *
 * Assumptions: this exceeds Testing Library's one-second default deliberately. Each case mounts a
 * whole antd screen into jsdom and waits on a transport settlement, a microtask and a React render,
 * and the suite runs its files in parallel workers under a container CPU quota -- so a wait that is
 * comfortable in isolation can exceed a second when every worker is busy. Raising the ceiling
 * weakens no assertion, because a wait only ever ends early on success.
 */
const ASYNC_CONDITION_TIMEOUT_MS = 5000;

/**
 * The row arity the baseline declares, asserted rather than assumed by `declaresTheMapsetRowArity`.
 *
 * Assumptions: seven is measured twice from two independent artifacts, which is why it is safe to
 * name it here as the expected value. `app/cbl/COCRDLIC.cbl` L177-L178 declares
 * `WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7`, its row loop at L1099 reads
 * `PERFORM VARYING I FROM 1 BY 1 UNTIL I > 7` and its screen array at L255 is
 * `WS-SCREEN-ROWS OCCURS 7 TIMES`; independently, `app/bms/COCRDLI.bms` carries exactly seven
 * row-field families, `CRDSEL1`-`CRDSEL7` paired with `ACCTNO1`-`ACCTNO7`, `CRDNUM1`-`CRDNUM7` and
 * `CRDSTS1`-`CRDSTS7`. A single source could be a transcription slip; two agreeing ones fix it.
 */
const DECLARED_ROW_ARITY = 7;

/**
 * The width `CRDSEL1I` through `CRDSEL7I` declare for a row's action character.
 *
 * Assumptions: one character, from `app/cpy-bms/COCRDLI.CPY` L78 (`CRDSEL1I PIC X(1)`) and its six
 * siblings. The terminal enforced this in hardware; `maxLength` is where it survives.
 */
const ROW_ACTION_DECLARED_WIDTH = 1;

/**
 * The four card-list sentences this program declares as `88` levels on its own message fields.
 *
 * Assumptions: this aliases the catalog rather than restating any sentence, so no literal from the
 * baseline is retyped anywhere in this file. `ui/src/messages/messages.ts` is the single transcription
 * of every `88`-level value and each entry carries the line of the `VALUE` clause it came from, which
 * is what lets `anchorsEverySentenceToItsDeclaringLine` compare provenance rather than prose.
 */
const CARD_LIST_STATUS = STATUS_MESSAGES.COCRDLIC;

/**
 * The three paging sentences this program moves into its error field at run time.
 *
 * Assumptions: these are separate from the `88`-level set above because they are written by `MOVE`
 * statements rather than declared as conditions, so their provenance is a list of statement lines
 * rather than one declaration line -- `PROGRAM_MESSAGE_SOURCES` records it that way.
 */
const CARD_LIST_PAGING = PROGRAM_MESSAGES.COCRDLIC;

/**
 * Builds an obviously-fake row selector of exactly the length the route contract demands.
 *
 * Assumptions: the padding character is drawn from the URL-safe alphabet
 * `ui/src/routes/cards.ts` accepts, so the value satisfies `isCardSelector` while remaining
 * self-evidently not a sealed token. Both properties are needed: the length is what the path builders
 * enforce, and the spelling is what stops a reader mistaking a fixture for a real selector.
 * @param {string} discriminator - A short suffix making this selector distinct from every other.
 * @returns {string} A selector of exactly the declared length.
 */
function selectorOfDeclaredLength(discriminator: string): string {
  const stem = `fake-selector-not-a-real-sealed-value-${discriminator}`;
  return stem.padEnd(CARD_SELECTOR_LENGTH, 'x');
}

/**
 * One browse row, in the four-member shape the card contract publishes.
 *
 * Assumptions: `displayCardNumber` is ALREADY masked when it arrives. `ui/src/api/types.ts` declares
 * `CardSummary` with exactly `key`, `displayCardNumber`, `accountId` and `activeStatus`, and the
 * service masks the primary account number to its last four digits before it is serialised -- so the
 * browse never holds a full number to leak, and there is no verification value in the shape at all.
 * A fixture carrying an unmasked number would be describing a body the service does not send.
 * @param {number} ordinal - Which row this is, used to make its account and its last four digits
 *   distinct from every other row's.
 * @returns {CardSummary} One row.
 */
function browseRow(ordinal: number): CardSummary {
  const lastFour = String(ordinal).padStart(4, '0');
  return {
    // Assumptions: the selector is opaque, and its LENGTH is nevertheless a contract rather than an
    //   incidental property. `ui/src/routes/cards.ts` declares `CARD_SELECTOR_LENGTH` as 59 and its
    //   path builders REFUSE any other length with a `RangeError`, so a shorter fixture would fail in
    //   the route helper rather than in the assertion it was written for. The value is spelled to say
    //   it seals nothing and is padded to the declared width from the URL-safe alphabet the
    //   `[A-Za-z0-9_-]` pattern allows.
    key: selectorOfDeclaredLength(lastFour),
    displayCardNumber: `************${lastFour}`,
    accountId: String(ordinal).padStart(11, '0'),
    // Assumptions: the domain is the two characters `CVACT02Y.cpy` allows for an active flag, so the
    //   rows alternate between them rather than all carrying one value, which would leave the
    //   inactive rendering unexercised.
    activeStatus: ordinal % 2 === 0 ? 'N' : 'Y',
  };
}

/**
 * A page holding exactly as many rows as the mapset has room for.
 *
 * Assumptions: a FULL page is what makes the per-row assertions meaningful. A table renders one row
 * per delivered item rather than the terminal's fixed seven, so a short fixture would render fewer
 * selection controls than the mapset declares and an assertion counting them would pass against a
 * screen that had lost some.
 */
const FULL_PAGE_ROWS: readonly CardSummary[] = Array.from(
  { length: DECLARED_ROW_ARITY },
  /**
   * Builds the row at one position of a full page.
   * @param {unknown} _unused - The array slot's value, always `undefined` here and not read.
   * @param {number} index - That row's zero-based position.
   * @returns {CardSummary} The row for that position.
   */
  function rowAt(_unused: unknown, index: number): CardSummary {
    return browseRow(index + 1);
  },
);

/** The first row of a full page, which the selection cases act on. */
const FIRST_ROW: CardSummary = FULL_PAGE_ROWS[0] ?? browseRow(1);

/** The second row of a full page, which the two-selection refusal case adds. */
const SECOND_ROW: CardSummary = FULL_PAGE_ROWS[1] ?? browseRow(2);

/**
 * Answers the next browse read with one page.
 *
 * Assumptions: the envelope is built by `pageResponse` from `./setup` rather than by hand, and that
 * is load-bearing rather than tidy. That builder produces exactly the four members every contract
 * publishes -- `items`, `firstKey`, `lastKey`, `hasNext` -- and its own note records why a fifth is
 * not merely unnecessary but wrong: no service sends a `hasPrev`, a page number, a page size or a
 * row total, so a hand-built fixture that invented one would let a case pass against a body the
 * server never produces. That is the one failure a fixture can cause which no assertion catches.
 * @param {readonly CardSummary[]} rows - The rows the page carries.
 * @param {boolean} hasNext - Whether reading forward from this page's trailing cursor yields another.
 * @returns {void} Nothing; the transport spy is programmed for its next call.
 */
function answerWithPage(rows: readonly CardSummary[], hasNext: boolean): void {
  vi.mocked(listCards).mockResolvedValue(pageResponse(rows, { hasNext }));
}

/**
 * The keyboard and pointer operator a render helper hands back.
 *
 * Assumptions: the type is DERIVED from the helper's own result rather than imported from the event
 * library, so the two cannot drift apart if the harness changes what it returns. Naming it also lets
 * the JSDoc below describe the return value in the same vocabulary the signature uses, instead of a
 * second spelling a reader would have to reconcile.
 */
type HarnessOperator = Awaited<ReturnType<typeof renderInAppShell>>['user'];

/**
 * Mounts the browse inside the application shell and waits for its opening read to settle.
 *
 * Assumptions: the shell is mounted rather than the screen alone, because the two things several
 * cases assert on are not the screen's own output. `ui/src/screens/cardList/index.tsx` L1905 hands
 * its message and its function-key bindings to the SHARED `useShellSlot`, so the message band and the
 * key legend are rendered by `ui/src/layout/AppShell.tsx`; a screen mounted bare paints neither and
 * every assertion about them would query an empty document.
 *
 * Assumptions: the render is awaited and then a settled condition is awaited again. The opening read
 * resolves on a microtask after mount, so returning before it settles leaves React applying state
 * outside `act` -- which Testing Library reports as a warning and which would make a following
 * assertion race the first page.
 * @param {readonly CardSummary[]} rows - The rows the opening page delivers.
 * @param {boolean} [hasNext] - Whether a further page exists, defaulting to none.
 * @returns {Promise<{ user: HarnessOperator }>} The keyboard and pointer operator bound to the
 *   rendered document, once the opening page has settled.
 */
async function renderBrowse(
  rows: readonly CardSummary[],
  hasNext = false,
): Promise<{ user: HarnessOperator }> {
  answerWithPage(rows, hasNext);

  const { user } = await renderInAppShell(<CardListScreen />, {
    // Assumptions: the pattern and the address are both `/cards`, which is the route
    //   `ui/src/router.tsx` publishes as `CARD_LIST_PATH`. The browse reads no route parameter, so
    //   the pattern exists only to mount the subject through the shell's own outlet rather than as a
    //   direct child -- the arrangement the router actually builds.
    initialEntries: ['/cards'],
    routePath: '/cards',
  });

  await waitFor(
    /**
     * Holds until the opening read has been issued and its page applied.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function openingReadSettled(): void {
      expect(vi.mocked(listCards)).toHaveBeenCalled();
      expect(browseTable()).toBeInTheDocument();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  return { user };
}

/**
 * Reads the rendered browse table.
 *
 * Assumptions: the table is found by ROLE rather than by a test identifier, because the assertion
 * several cases make is that the browse is composed from the design system's `Table` -- and a role is
 * what that component contributes to the accessibility tree. A test identifier would be satisfied by
 * a raw element carrying the same attribute.
 * @returns {HTMLElement} The table element.
 * @throws {Error} If no table is rendered, which Testing Library raises.
 */
function browseTable(): HTMLElement {
  return screen.getByRole('table');
}

/**
 * Reads the body rows of the rendered browse.
 *
 * Assumptions: the header row is excluded by querying rows inside `rowgroup`s and dropping the first,
 * because antd renders its header and its body as separate row groups and `getAllByRole('row')` spans
 * both -- so a count taken without this would be one higher than the page it is describing.
 * @returns {readonly HTMLElement[]} One element per delivered row, in render order.
 */
function browseBodyRows(): readonly HTMLElement[] {
  const groups = within(browseTable()).getAllByRole('rowgroup');
  const body = groups[groups.length - 1];
  if (body === undefined) {
    return [];
  }
  return within(body).queryAllByRole('row');
}

/**
 * Reads every per-row action entry the browse rendered.
 *
 * Assumptions: the controls are found by their accessible name, which the screen takes from the
 * mapset's own column label -- `CARD_LIST_LABELS.selectColumn` trimmed. Querying by label is what
 * ties the assertion to the transcribed heading rather than to a DOM shape that could change without
 * changing what an operator reads.
 * Assumptions: the QUERY form is used rather than the get form, because an empty result page renders
 * no rows and therefore no entries at all -- and `getAllBy*` throws on an empty match, which would
 * turn a legitimate assertion of absence into an error.
 * @returns {readonly HTMLElement[]} One entry control per rendered row, empty when the page has none.
 */
function rowActionEntries(): readonly HTMLElement[] {
  return screen.queryAllByLabelText(CARD_LIST_LABELS.selectColumn.trim());
}

/**
 * Reads one per-row action entry by position, narrowing away the absent case.
 *
 * Assumptions: this NARROWS rather than casting. `ui/tsconfig.json` sets `noUncheckedIndexedAccess`,
 * so indexing the entry list yields a possibly-absent element, and a type assertion would silence
 * that without establishing anything. Throwing on the absent case is what makes the returned value
 * genuinely an element, and a case that asked for a row the page never delivered fails here with a
 * sentence naming the position rather than later on a property of undefined.
 * @param {number} index - The row's zero-based position among the rendered rows.
 * @returns {HTMLElement} That row's action entry.
 * @throws {Error} If the page rendered no row at that position.
 */
function rowActionEntry(index: number): HTMLElement {
  const entry = rowActionEntries()[index];
  if (entry === undefined) {
    throw new Error(`the rendered page has no row at position ${String(index)}`);
  }
  return entry;
}

/**
 * Reads the query one recorded browse call carried.
 * @param {number} index - Which call to read, counting from zero.
 * @returns {CardListQuery} That call's query, or an empty query if the call was never made.
 */
function recordedQuery(index: number): CardListQuery {
  return vi.mocked(listCards).mock.calls[index]?.[0] ?? {};
}

/**
 * Counts the browse reads issued so far.
 * @returns {number} How many times the transport was called.
 */
function readCount(): number {
  return vi.mocked(listCards).mock.calls.length;
}

/**
 * Reads the function-key legend the shell rendered for this screen.
 *
 * Assumptions: the legend is a navigation landmark named by `PF_KEY_BAR_REGION_LABEL`, which is how
 * `ui/src/layout/PfKeyBar.tsx` publishes it. Querying the landmark rather than the shell footer
 * scopes the descriptor assertions to the bar itself, so an unrelated control elsewhere in the footer
 * could not satisfy them.
 * @returns {HTMLElement} The legend landmark.
 * @throws {Error} If the shell rendered no legend, which Testing Library raises.
 */
function keyLegend(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Reads the accessible names of the controls the key legend advertises, in render order.
 * @returns {readonly string[]} One descriptor per advertised key.
 */
function advertisedKeyDescriptors(): readonly string[] {
  return within(keyLegend())
    .getAllByRole('button')
    .map(
      /**
       * Reads one legend control's visible descriptor.
       * @param {HTMLElement} control - One control in the legend.
       * @returns {string} Its text, exactly as rendered.
       */
      function descriptorOf(control: HTMLElement): string {
        return control.textContent ?? '';
      },
    );
}

/**
 * Presses one legend control by the descriptor it advertises.
 *
 * Assumptions: this drives the POINTER path deliberately, as the counterpart to `pressPfKey`'s
 * keyboard path. The 3270 original was keyboard-only, so the binding is the contract and the bar is
 * an addition; a case that only clicked would pass in full while every keyboard binding was broken,
 * and a case that only typed would pass while the bar dispatched nothing. Both paths are asserted to
 * reach the same handler.
 * @param {HarnessOperator} user - The pointer operator.
 * @param {string} descriptor - The descriptor the control advertises.
 * @returns {Promise<void>} Resolves once the click has been dispatched and settled.
 */
async function clickLegendKey(user: HarnessOperator, descriptor: string): Promise<void> {
  await user.click(within(keyLegend()).getByRole('button', { name: descriptor }));
}

/**
 * Reads the source of the screen under test.
 *
 * Assumptions: a prop passed to a component is not observable in the DOM, so the one honest way to
 * assert that `pagination={false}` is genuinely SET is to read the module that sets it. The
 * alternative -- asserting that no pager is visible -- is what this exists to avoid: antd hides its
 * pager when a page holds fewer rows than the page size, so an invisible pager would still be slicing
 * rows client-side and the visual assertion would pass over it.
 *
 * Assumptions: the `join(import.meta.dirname, ...)` spelling is used rather than
 * `new URL('...', import.meta.url)`, because Vite rewrites that exact syntax as an asset reference
 * and the read would resolve to a bundled URL rather than the file.
 * `ui/src/screens/cardList/browseNarrowing.test.tsx` records the same finding.
 * @returns {string} The screen module's text.
 * @throws {Error} If the module cannot be read, which the file system raises.
 */
function screenSource(): string {
  return readFileSync(join(import.meta.dirname, '../screens/cardList/index.tsx'), 'utf8');
}

/**
 * Counts occurrences of a token on the CODE lines of a module, ignoring its comments.
 *
 * Assumptions: comment lines are excluded because this tree documents its non-obvious props in
 * rationale blocks that name them, so a token counted across the whole text would include every
 * mention of it in prose. The filter keys on the line's opening characters, which is sufficient here
 * because the block-comment style in use puts a leading asterisk on every continuation line.
 * @param {string} source - The module text.
 * @param {string} token - The token to count.
 * @returns {number} How many code lines mention it.
 */
function countCodeOccurrences(source: string, token: string): number {
  return source.split('\n').filter(
    /**
     * Tests whether one line is a code line mentioning the token.
     * @param {string} line - One line of the module.
     * @returns {boolean} Whether it mentions the token outside a comment.
     */
    function mentionsInCode(line: string): boolean {
      const trimmed = line.trimStart();
      const isComment =
        trimmed.startsWith('*') || trimmed.startsWith('//') || trimmed.startsWith('/*');
      return !isComment && line.includes(token);
    },
  ).length;
}

/**
 * Finds one catalogued sentence inside the shell's message band.
 *
 * Assumptions: the assertion is SCOPED to the band rather than made over the whole document, because
 * a refused screen renders its sentence twice on purpose -- once in the row-23 band an operator reads,
 * and once in a visually-hidden element that a field's `aria-describedby` resolves to, so a screen
 * reader announces the reason on the control itself. An unscoped query finds both and fails as
 * ambiguous, and the ambiguity is a feature of the accessible rendering rather than a defect.
 *
 * Assumptions: the non-collapsing normaliser is preserved, which is the reason this wraps a query
 * rather than reading the band's text. Testing Library's default normaliser collapses runs of
 * whitespace, and several baseline sentences carry doubled spaces that are content; a collapsing
 * matcher would accept a screen that emitted one space where the source has two.
 * @param {string} expected - The catalogued sentence, taken from the message catalog and not retyped.
 * @returns {HTMLElement} The element inside the band carrying that sentence.
 * @throws {Error} If the band carries no such sentence, which Testing Library raises.
 */
function expectVerbatimMessageInBand(expected: string): HTMLElement {
  return within(screen.getByTestId(MESSAGE_BAND_TEST_ID)).getByText(expected, {
    normalizer: getDefaultNormalizer({ trim: true, collapseWhitespace: false }),
  });
}

/**
 * Finds one catalogued sentence inside the shell's INFORMATIONAL band.
 *
 * Assumptions: a second scoped locator rather than a parameter on the one above, because the two bands
 * are two different fields of the mapset and a case asserting on one nearly always asserts the other is
 * quiet. `ui/src/layout/MessageBand.tsx` publishes a distinct identifier per channel for exactly that
 * reason, and naming them separately here keeps each call site saying which of the mapset's two message
 * fields it means.
 *
 * Assumptions: the non-collapsing normaliser is preserved for the reason recorded above -- several
 * baseline sentences carry doubled spaces that are content rather than layout.
 * @param {string} expected - The catalogued sentence, taken from the message catalog and not retyped.
 * @returns {HTMLElement} The element inside the informational band carrying that sentence.
 * @throws {Error} If that band carries no such sentence, which Testing Library raises.
 */
function expectVerbatimMessageInInformationBand(expected: string): HTMLElement {
  return within(screen.getByTestId(INFORMATION_BAND_TEST_ID)).getByText(expected, {
    normalizer: getDefaultNormalizer({ trim: true, collapseWhitespace: false }),
  });
}

/**
 * Reads the whole rendered document as text.
 *
 * Assumptions: the data-exposure cases assert on the WHOLE document rather than on the table alone,
 * because a leak is not confined to the column it belongs to -- an unmasked number could reach a
 * title attribute, a hidden description or the message band. Searching everything is what makes the
 * absence claim meaningful.
 * @returns {string} Every character the document renders.
 */
function renderedText(): string {
  return document.body.textContent ?? '';
}

/**
 * The direction a forward step declares on the wire.
 *
 * Assumptions: the wire value is `next` rather than a word like `FORWARD`, because
 * `ui/src/api/types.ts` L131 declares `PageDirection` as the closed union `'next' | 'previous'`. The
 * constant is typed so a drift in that union fails here at compile time rather than in an assertion.
 */
const FORWARD_DIRECTION: PageDirection = 'next';

/** The direction a backward step declares on the wire, from the same closed union. */
const BACKWARD_DIRECTION: PageDirection = 'previous';

/**
 * Asserts the browse declares the row arity the baseline measured twice.
 *
 * Assumptions: three things are checked because "page size is seven" is three separate claims. The
 * exported constant must BE seven; the screen must SUPPLY it to the shared browse state, which
 * `ui/src/hooks/usePagedQuery.ts` takes as a required option; and a full page must actually RENDER
 * seven rows with seven action entries. Asserting only the constant would pass against a screen that
 * declared seven and paged by some other number.
 * @returns {Promise<void>} Resolves once the opening page has settled and been counted.
 */
async function declaresTheMapsetRowArity(): Promise<void> {
  // WHY : Assumptions: seven is the figure `app/cbl/COCRDLIC.cbl` L177-L178 declares as
  //       `WS-MAX-SCREEN-LINES PIC S9(4) COMP VALUE 7`, that its row loop at L1099 bounds itself by,
  //       and that its screen array at L255 dimensions with `OCCURS 7 TIMES`; independently it is the
  //       number of row-field families in `app/bms/COCRDLI.bms`, `CRDSEL1`-`CRDSEL7` with their
  //       matching `ACCTNO`, `CRDNUM` and `CRDSTS` sets. Two artifacts agreeing is what makes this a
  //       citation rather than a magic number.
  expect(CARD_LIST_PAGE_SIZE).toBe(DECLARED_ROW_ARITY);

  // WHY : Assumptions: the wiring is asserted lexically because `pageSize` is an option passed to a
  //       hook, which leaves no trace in the DOM. `usePagedQuery` REQUIRES the option, so a screen
  //       that omitted it would not compile -- what this catches is a screen that supplies a
  //       different number while the exported constant still reads seven.
  expect(screenSource()).toContain('pageSize: CARD_LIST_PAGE_SIZE');

  await renderBrowse(FULL_PAGE_ROWS);

  expect(browseBodyRows()).toHaveLength(DECLARED_ROW_ARITY);
  expect(rowActionEntries()).toHaveLength(DECLARED_ROW_ARITY);
}

/**
 * The browse holds its own horizontal overflow instead of pushing the page sideways.
 *
 * ⚠️ Purpose: a browser sweep measured this grid painting 8.92 pixels OUTSIDE the viewport at 375 --
 * table rect right 383.92 against an inner width of 375 -- and the page body absorbing the remainder:
 * `#carddemo-shell-content` reported `scrollWidth` 384 against `clientWidth` 375 and accepted a
 * `scrollLeft` of 9, while `.ant-table-content` and all NINE of its ancestors computed
 * `overflow-x: visible` and refused a `scrollLeft` entirely. A body that pans sideways slides the
 * title band, the message line and the key legend out from under the operator, so three persistent
 * zones were being paid for one column. The same sweep found the visible consequence at 375 and 576:
 * an eleven-digit account number wrapping as `000000` / `00100` and a masked card number over three
 * lines, which for a card system is a misreading risk rather than a cosmetic one.
 *
 * ⚠️ Assumptions: both halves are asserted because neither alone is the fix.
 * `@rc-component/table/lib/Table.js` L259-L272 turns a DECLARED horizontal extent into
 * `overflow-x: auto` on the scrolling region, so without the extent the grid has no scroller to hold
 * the overflow in; and L426-L442 infers a fixed layout only for a pinned column, a pinned header, a
 * sticky grid or an ellipsised column -- this grid has none of the four, so under the inferred
 * automatic layout a declared column width is a MINIMUM the content may grow past, and the sibling
 * user browse measured a one-character column at roughly 700 pixels that way.
 *
 * Assumptions: the four column measures are asserted against cells transcribed from the mapset here
 * rather than imported from the screen, so this case agrees with the ORACLE rather than with the
 * subject. `app/bms/COCRDLI.bms` heads row 9 at columns 10, 21, 45 and 66, the last over `LENGTH=7`,
 * so the four columns span columns 10 through 72 -- 63 character cells.
 * @returns {Promise<void>} Resolves once the settled grid has been measured.
 */
async function holdsItsOwnHorizontalOverflow(): Promise<void> {
  /** Character cells each column spans in `app/bms/COCRDLI.bms`, in the mapset's own order. */
  const mapsetCells = [11, 24, 21, 7];

  expect(
    screenSource(),
    'the grid must declare a horizontal extent, which is what creates its scroller',
  ).toContain('scroll={{ x: cardListTableMeasure(cssVar) }}');
  expect(
    screenSource(),
    'and must state the fixed layout the library will not infer for an unpinned grid',
  ).toContain('tableLayout="fixed"');
  expect(
    screenSource(),
    'each column must claim its own cells plus the padding on both of its edges',
  ).toContain('characterCellColumnMeasure(CARD_LIST_COLUMN_CELLS.select, options.tokens.padding)');

  answerWithPage(FULL_PAGE_ROWS, false);

  const { container } = await renderInAppShell(<CardListScreen />, {
    initialEntries: ['/cards'],
    routePath: '/cards',
  });

  await waitFor(
    /**
     * Holds until the opening page has been applied.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function gridSettled(): void {
      expect(browseBodyRows()).toHaveLength(DECLARED_ROW_ARITY);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  /*
   * WHY : Assumptions: the traversal is a plain loop rather than `map` and `filter`, for the reason the
   *       file header records -- `ui/eslint.config.js` selects a function expression in every position,
   *       so an inline callback owes its own JSDoc block.
   */
  const declared: string[] = [];
  const proportional: string[] = [];

  for (const column of container.querySelectorAll<HTMLElement>('.ant-table-content col')) {
    declared.push(column.style.width);

    if (column.style.width.includes('%')) {
      proportional.push(column.style.width);
    }
  }

  expect(declared, 'the grid declares one width per mapset column').toHaveLength(
    mapsetCells.length,
  );
  expect(
    proportional,
    'no column may take a PROPORTIONAL share, which is what starved the narrowest one',
  ).toEqual([]);

  let spanned = 0;

  for (const [index, cells] of mapsetCells.entries()) {
    spanned += cells;
    expect(
      declared[index],
      `column ${String(index)} must claim its own ${String(cells)} cells`,
    ).toContain(`${String(cells)}ch`);
    expect(
      declared[index],
      `column ${String(index)} must also reserve the padding on both of its edges`,
    ).toContain('2 * ');
  }

  expect(spanned, 'and the four spans must sum to the mapset row the extent is derived from').toBe(
    63,
  );
}

/**
 * Asserts the design system's own offset pagination is genuinely switched off.
 *
 * ⚠️ Refactoring Rationale: offset pagination was rejected on a correctness argument rather than a
 * preference. Under concurrent inserts an offset SKIPS AND REPEATS rows -- a row inserted before the
 * current offset shifts every later row by one, so the next page omits one row and re-shows another
 * -- which changes observable behaviour that browse-by-key does not, because a key names a position
 * that inserts cannot move. The baseline pages by key (`app/cbl/COCRDLIC.cbl` L230-L244), so
 * adopting offsets would have introduced a behavioural divergence with nothing asking for it.
 *
 * Assumptions: the prop is asserted at its source AND the rendered pager is asserted absent, because
 * neither check alone is sufficient. antd hides its pager when a page holds fewer rows than its page
 * size, so a visual check alone would pass over a pager that was still slicing rows client-side;
 * and a lexical check alone would pass if the prop were set on some other table.
 * @returns {Promise<void>} Resolves once the page has settled and the pager been shown absent.
 */
async function disablesTheDesignSystemOffsetPager(): Promise<void> {
  expect(screenSource()).toContain('pagination={false}');

  await renderBrowse(FULL_PAGE_ROWS, true);

  // WHY : Assumptions: the pager is located by the design system's own class rather than by role,
  //       because antd renders it as a list of items whose role would collide with any other list on
  //       the screen. The class is the component's published surface and is what a pager would carry
  //       if one were rendered at all.
  expect(document.querySelector('.ant-pagination')).toBeNull();
}

/**
 * Asserts the browse renders exactly the rows the envelope delivered, and nothing else.
 *
 * Assumptions: the envelope's `items` are the whole of what a page shows. The baseline reads rows
 * into a fixed seven-slot array and paints the slots it filled, so a screen that padded a short page
 * to seven or dropped a delivered row would diverge from it. Both directions are checked: the count
 * matches, and every delivered row's own rendering is present.
 * @returns {Promise<void>} Resolves once every delivered row has been located.
 */
async function rendersExactlyTheDeliveredRows(): Promise<void> {
  const shortPage = FULL_PAGE_ROWS.slice(0, 3);
  await renderBrowse(shortPage);

  expect(browseBodyRows()).toHaveLength(shortPage.length);

  for (const row of shortPage) {
    expect(screen.getByText(row.displayCardNumber)).toBeInTheDocument();
    expect(screen.getByText(row.accountId)).toBeInTheDocument();
  }
}

/**
 * Asserts a forward step replays the page's TRAILING cursor rather than an ordinal.
 *
 * Assumptions: this is the direct analogue of the baseline's own forward read. `app/cbl/COCRDLIC.cbl`
 * L230-L232 carries `WS-CA-LAST-CARDKEY` across the pseudo-conversational gap and reads onward from
 * it, and L1284-L1285 discovers whether a further page exists by computing
 * `WS-MAX-SCREEN-LINES + 1` -- reading one more record than the screen has room for. The target
 * expresses the same two ideas as the envelope's `lastKey` and its `hasNext`.
 * @returns {Promise<void>} Resolves once the forward read has been recorded and inspected.
 */
async function stepsForwardOnTheTrailingCursor(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);

  await pressPfKey(user, 'PFK08');

  await waitFor(
    /**
     * Holds until the forward read has been issued.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function forwardReadIssued(): void {
      expect(readCount()).toBe(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  // WHY : Assumptions: the cursor is compared against the token `pageResponse` reported as this
  //       page's trailing position, so the assertion is that the screen replayed what it was GIVEN
  //       rather than that it produced a particular string. The cursor is opaque -- the service seals
  //       the direction into it -- so a client that parsed or computed on it would be reasoning about
  //       an encoding it does not own.
  expect(recordedQuery(1)).toStrictEqual({
    cursor: TRAILING_CURSOR,
    direction: FORWARD_DIRECTION,
  });
}

/**
 * Asserts a backward step replays the page's LEADING cursor.
 *
 * Assumptions: a forward step is taken first because backward availability is the client's own page
 * ordinal and the first page has none. `ui/src/hooks/usePagedQuery.ts` derives it as
 * `pageNumber > FIRST_PAGE_NUMBER && firstKey !== null`, which mirrors the baseline exactly: the
 * program holds `WS-CA-SCREEN-NUM` at L237 with `88 CA-FIRST-PAGE VALUE 1` and refuses the backward
 * step on that condition at L901-L903. The envelope itself carries no backward flag, and this case is
 * what proves the screen does not need one.
 * @returns {Promise<void>} Resolves once the backward read has been recorded and inspected.
 */
async function stepsBackwardOnTheLeadingCursor(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);

  await pressPfKey(user, 'PFK08');
  await waitFor(
    /**
     * Holds until the browse has advanced past its first page.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function secondPageApplied(): void {
      expect(readCount()).toBe(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  await pressPfKey(user, 'PFK07');
  await waitFor(
    /**
     * Holds until the backward read has been issued.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function backwardReadIssued(): void {
      expect(readCount()).toBe(3);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(recordedQuery(2)).toStrictEqual({
    cursor: LEADING_CURSOR,
    direction: BACKWARD_DIRECTION,
  });
}

/**
 * Asserts no request ever carries a page ordinal, an offset or a row total.
 *
 * ⚠️ Assumptions: `ui/src/hooks/usePagedQuery.ts` publishes `pageNumber`, and it is DISPLAY-ONLY --
 * it exists so a screen can paint which page an operator is on, exactly as the baseline paints
 * `PAGENOI` from `WS-CA-SCREEN-NUM`, and it is never an input to a read. This case fixes that
 * distinction, because an ordinal that reached the wire would reintroduce offset paging through the
 * back door while every cursor assertion above still passed.
 * @returns {Promise<void>} Resolves once every recorded request has been inspected.
 */
async function sendsNoOrdinalOnAnyRequest(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);

  await pressPfKey(user, 'PFK08');
  await waitFor(
    /**
     * Holds until both the opening and the forward read have been recorded.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function bothReadsIssued(): void {
      expect(readCount()).toBe(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  // WHY : Assumptions: the permitted set is closed and comes from the contract rather than from this
  //       file's imagination -- `ui/src/api/types.ts` declares `CardListQuery` with exactly
  //       `accountId`, `cursor` and `direction`. Asserting membership of a closed set catches a member
  //       nobody thought to forbid, which a list of forbidden names would not.
  for (let call = 0; call < readCount(); call += 1) {
    expect(Object.keys(recordedQuery(call)).every(isPermittedQueryMember)).toBe(true);
  }
}

/**
 * The only three members the card browse query contract publishes.
 *
 * Assumptions: this is the whole of `CardListQuery` as `ui/src/api/types.ts` L1342-L1345 declares it.
 * Holding it as a list rather than inline in the assertion is what lets the predicate below be a named
 * declaration, which `ui/eslint.config.js` requires of every function it can reach.
 */
const PERMITTED_QUERY_MEMBERS: readonly string[] = ['accountId', 'cursor', 'direction'];

/**
 * Tests whether one recorded request member belongs to the published query contract.
 *
 * Alternatives Considered: passing `Set.prototype.has` with the set as a `thisArg`, which is shorter.
 * Rejected because `@typescript-eslint/unbound-method` refuses a method separated from its receiver --
 * correctly, since the binding is invisible at the call site and a later refactor that dropped the
 * second argument would leave a predicate whose `this` is undefined.
 * @param {string} member - One property name read from a recorded query.
 * @returns {boolean} Whether the contract declares that member.
 */
function isPermittedQueryMember(member: string): boolean {
  return PERMITTED_QUERY_MEMBERS.includes(member);
}

/**
 * Asserts the backward step is refused on the first page with the baseline's own sentence.
 *
 * Assumptions: the key stays live and reports, rather than being disabled. That is the baseline's
 * behaviour and not a simplification of it: `app/cbl/COCRDLIC.cbl` L901-L903 dispatches PF7 on the
 * first page into a `MOVE` of this sentence, so the operator presses the key and is told. A disabled
 * control would remove the telling, which is the part the sentence exists for.
 * @returns {Promise<void>} Resolves once the refusal has been shown and the read count checked.
 */
async function refusesTheBackwardStepOnTheFirstPage(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);

  await pressPfKey(user, 'PFK07');

  await waitFor(
    /**
     * Holds until the refusal sentence has been painted.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function refusalPainted(): void {
      // WHY : Assumptions: the expected text comes from the catalog entry whose provenance
      //       `PROGRAM_MESSAGE_SOURCES.COCRDLIC.NO_PREVIOUS_PAGES_TO_DISPLAY` records as
      //       `app/cbl/COCRDLIC.cbl` L903. Passing the catalog value asserts the screen agrees with
      //       the TRANSCRIPTION; a retyped literal would only assert it agrees with this file.
      expectVerbatimMessage(CARD_LIST_PAGING.NO_PREVIOUS_PAGES_TO_DISPLAY);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  // WHY : Assumptions: the refusal must not also read. The opening page is the only read, so a second
  //       one would mean the screen replayed a cursor it had already been told not to have.
  expect(readCount()).toBe(1);
}

/**
 * Asserts the forward boundary reports end-of-records first and end-of-pages on the next press.
 *
 * ⚠️ Assumptions: these are TWO sentences reached in sequence, not one, and the order is the
 * baseline's. `app/cbl/COCRDLIC.cbl` L905-L908 gates `NO MORE PAGES TO DISPLAY` on PF8 together with
 * BOTH `CA-NEXT-PAGE-NOT-EXISTS` and `CA-LAST-PAGE-SHOWN`, and the arm immediately below it is what
 * SETS `CA-LAST-PAGE-SHOWN` -- so the first press at the end of the browse cannot satisfy that gate
 * and falls to the end-of-records sentence written at L1219 and L1239, while the second press
 * satisfies it. A case that pressed once and expected the pages sentence would be asserting a state
 * the program reaches only on the press after.
 *
 * Assumptions: the end-of-records sentence is ONE catalogued entry whose provenance is a two-element
 * line list, because the baseline writes the identical literal from two different arms -- L1219 on the
 * inner end-of-file and L1239 on the outer one. The duplication is deliberate in the source and is
 * recorded rather than collapsed.
 * @returns {Promise<void>} Resolves once both sentences have been shown in order.
 */
async function reportsTheEndOfRecordsThenTheEndOfPages(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, false);

  await pressPfKey(user, 'PFK08');
  await waitFor(
    /**
     * Holds until the end-of-records sentence has been painted.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function endOfRecordsPainted(): void {
      expectVerbatimMessage(CARD_LIST_PAGING.NO_MORE_RECORDS_TO_SHOW);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  await pressPfKey(user, 'PFK08');
  await waitFor(
    /**
     * Holds until the end-of-pages sentence has replaced it.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function endOfPagesPainted(): void {
      expectVerbatimMessage(CARD_LIST_PAGING.NO_MORE_PAGES_TO_DISPLAY);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  // WHY : Assumptions: neither press reads. `hasNext` is false, so there is no trailing cursor to
  //       replay, and a read issued anyway would be a request the service must refuse.
  expect(readCount()).toBe(1);
}

/**
 * Asserts both filter entries refuse more characters than their symbolic map declares.
 *
 * Assumptions: the widths are citations rather than choices. `app/cpy-bms/COCRDLI.CPY` declares
 * `ACCTSIDI PIC X(11)` at L66 and `CARDSIDI PIC X(16)` at L72, and the terminal enforced those two
 * widths in hardware -- an eleventh character filled the account field and a twelfth did nothing.
 * `maxLength` is the browser control that refuses the same keystroke.
 *
 * Assumptions: the screen's own exported constants are compared against the declared widths rather
 * than the numbers being written into the assertion twice. The screen names them
 * `CARD_LIST_ACCOUNT_FILTER_WIDTH` and `CARD_LIST_CARD_FILTER_WIDTH` precisely so the copybook width
 * has one home, and checking the constant AND the rendered attribute catches a control wired to the
 * wrong constant as well as a constant given the wrong value.
 * @returns {Promise<void>} Resolves once both controls have been measured.
 */
async function boundsBothFilterEntriesToTheirDeclaredWidths(): Promise<void> {
  // WHY : Assumptions: eleven and sixteen are `app/cpy-bms/COCRDLI.CPY` L66 and L72 respectively.
  expect(CARD_LIST_ACCOUNT_FILTER_WIDTH).toBe(11);
  expect(CARD_LIST_CARD_FILTER_WIDTH).toBe(16);

  await renderBrowse(FULL_PAGE_ROWS);

  expectMaxLength(
    screen.getByLabelText(CARD_LIST_LABELS.accountNumberFilter.replace(/\s+/gu, ' ').trim()),
    CARD_LIST_ACCOUNT_FILTER_WIDTH,
  );
  expectMaxLength(
    screen.getByLabelText(CARD_LIST_LABELS.cardNumberFilter.replace(/\s+/gu, ' ').trim()),
    CARD_LIST_CARD_FILTER_WIDTH,
  );
}

/**
 * Asserts every per-row action entry accepts exactly one character.
 *
 * Assumptions: a FULL page is rendered so that all seven of the mapset's row families are exercised.
 * `app/cpy-bms/COCRDLI.CPY` declares `CRDSEL1I PIC X(1)` at L78 and its six siblings identically, so
 * seven controls each bounded to one character is the whole of the declaration -- and a short page
 * would render fewer controls, letting a regression in the later rows pass unseen.
 * @returns {Promise<void>} Resolves once all seven entries have been measured.
 */
async function boundsEveryRowActionEntryToOneCharacter(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  const entries = rowActionEntries();
  expect(entries).toHaveLength(DECLARED_ROW_ARITY);

  for (const entry of entries) {
    // WHY : Assumptions: one character, from `app/cpy-bms/COCRDLI.CPY` L78 and its six siblings.
    expectMaxLength(entry, ROW_ACTION_DECLARED_WIDTH);
  }
}

/**
 * Asserts the opening cursor rests on exactly one control.
 *
 * Assumptions: exactly one, because `app/bms/COCRDLI.bms` carries the `IC` attribute exactly once
 * across its 72 `DFHMDF` definitions -- at L89, on `ACCTSID`. A 3270 map can only place the cursor
 * once, so a second autofocused control would be a state the terminal could not represent, and in a
 * browser it would mean whichever control initialised last silently won.
 * @returns {Promise<void>} Resolves once the focused control has been identified and counted.
 */
async function placesTheOpeningCursorOnOneControl(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  // WHY : ⚠️ Assumptions: the count is taken from the SOURCE rather than from the DOM, because React
  //       applies `autoFocus` by focusing the element imperatively and does NOT emit an `autofocus`
  //       attribute -- so `input[autofocus]` matches nothing however many controls carry the prop, and
  //       an attribute-based count would report zero and pass a `not.toBeGreaterThan` style assertion
  //       while saying nothing at all. Comment lines are excluded because the screen also DISCUSSES
  //       the prop in a rationale block, so a naive text count reads two. One is the figure
  //       `app/bms/COCRDLI.bms` L89 fixes, where `ATTRB=(FSET,IC,NORM,UNPROT)` is the only `IC` among
  //       the mapset's 72 field definitions.
  expect(countCodeOccurrences(screenSource(), 'autoFocus')).toBe(1);

  // WHY : Assumptions: the account filter is the control the baseline points at, so the count above is
  //       checked against the identity as well -- one autofocus on the wrong field would satisfy a
  //       count alone.
  expect(document.activeElement).toBe(
    screen.getByLabelText(CARD_LIST_LABELS.accountNumberFilter.replace(/\s+/gu, ' ').trim()),
  );
}

/**
 * Asserts the browse renders no masked-entry control anywhere.
 *
 * ⚠️ Assumptions: the six `ATTRB=(ASKIP,DRK,FSET)` fields in `app/bms/COCRDLI.bms` are NOT password
 * fields, and this case exists because `DRK` naturally reads as though they were. Measured: all six
 * occurrences of that attribute set in the whole repository are in this one mapset, and they are
 * non-display per-row CARRIER fields -- the map holds a value the program needs on the next turn
 * without painting it, which is what non-display means on a 3270. The system's only genuine password
 * fields are in `app/bms/COSGN00.bms`, `app/bms/COUSR01.bms` and `app/bms/COUSR02.bms`, none of which
 * is this screen. So the correct assertion is that NO masked control is rendered here.
 *
 * Assumptions: the carrier fields render nothing at all in the target rather than being reproduced as
 * hidden inputs, because the state they carried across the pseudo-conversational gap is now held in
 * component state -- there is no gap to carry it across. `ui/src/screens/cardList/index.tsx` records
 * the same conclusion at its selection column.
 * @returns {Promise<void>} Resolves once the absence has been established.
 */
async function rendersNoMaskedEntryControl(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  expect(document.querySelectorAll('input[type="password"]')).toHaveLength(0);
}

/**
 * Asserts the browse is composed from design-system components rather than raw markup.
 *
 * Assumptions: the check is that each rendered control carries the class its design-system component
 * emits, which is the observable consequence of using the component rather than an element. A raw
 * `<table>`, `<input>` or `<button>` would satisfy a role query identically, so a role assertion alone
 * cannot tell the two apart -- and the migration plan's zero-hardcoded-values rule depends on the
 * component being the thing rendered, because that is what puts every design value on the theme's
 * token surface instead of in bespoke CSS.
 * @returns {Promise<void>} Resolves once the table, the entries and the controls have been inspected.
 */
async function composesTheBrowseFromDesignSystemComponents(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  expect(browseTable().closest('.ant-table')).not.toBeNull();

  for (const entry of rowActionEntries()) {
    expect(entry.classList.contains('ant-input')).toBe(true);
  }

  for (const control of within(keyLegend()).getAllByRole('button')) {
    expect(control.classList.contains('ant-btn')).toBe(true);
  }
}

/**
 * Asserts every catalogued card-list sentence is anchored to the baseline line that declares it.
 *
 * Assumptions: this asserts PROVENANCE rather than prose, which is the only form of message assertion
 * that cannot be satisfied by a paraphrase. `ui/src/messages/messages.ts` records, beside each
 * transcription, the line of the baseline statement it came from; comparing those recorded lines
 * against the lines read out of `app/cbl/COCRDLIC.cbl` during migration ties the catalog to the
 * source, and every rendering assertion elsewhere in this file then compares the screen against the
 * catalog. The chain is screen to catalog to cited line, with no retyped sentence at any link.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function anchorsEverySentenceToItsDeclaringLine(): void {
  // WHY : Assumptions: these five are `88`-level conditions declared on this program's own message
  //       fields, so each has exactly ONE declaring line -- the `VALUE` clause -- at
  //       `app/cbl/COCRDLIC.cbl` L116, L120, L122, L124 and L126 in turn.
  expect(CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.line).toBe(116);
  expect(CARD_LIST_STATUS.WS_EXIT_MESSAGE.line).toBe(120);
  expect(CARD_LIST_STATUS.WS_NO_RECORDS_FOUND.line).toBe(122);
  expect(CARD_LIST_STATUS.WS_MORE_THAN_1_ACTION.line).toBe(124);
  expect(CARD_LIST_STATUS.WS_INVALID_ACTION_CODE.line).toBe(126);

  // WHY : Assumptions: the two message fields have different declared widths -- `WS-INFO-MSG` is
  //       `PIC X(45)` at `app/cbl/COCRDLIC.cbl` L112 and `WS-ERROR-MSG` is `PIC X(75)` at L117 -- so
  //       the width travels with the entry rather than being one number for the screen. The
  //       information line is the narrower of the two, and merging them would silently widen it.
  expect(CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.declaredWidth).toBe(45);
  expect(CARD_LIST_STATUS.WS_INVALID_ACTION_CODE.declaredWidth).toBe(75);

  // WHY : Assumptions: these three are written by `MOVE` statements rather than declared as
  //       conditions, so their provenance is a LIST of statement lines. The end-of-records sentence
  //       has two because the baseline writes the identical literal from two arms -- the inner
  //       end-of-file at L1219 and the outer one at L1239 -- and that duplication is a property of
  //       the source, recorded rather than collapsed to one.
  expect(PROGRAM_MESSAGE_SOURCES.COCRDLIC.NO_PREVIOUS_PAGES_TO_DISPLAY).toStrictEqual([903]);
  expect(PROGRAM_MESSAGE_SOURCES.COCRDLIC.NO_MORE_PAGES_TO_DISPLAY).toStrictEqual([908]);
  expect(PROGRAM_MESSAGE_SOURCES.COCRDLIC.NO_MORE_RECORDS_TO_SHOW).toStrictEqual([1219, 1239]);

  // WHY : Assumptions: the two filter refusals are SHARED with the card-detail and card-update
  //       programs, so their provenance is a list of files. Only this program's entry is asserted
  //       here, at `app/cbl/COCRDLIC.cbl` L1022 and L1058; the sibling entries belong to the sibling
  //       screens' own cases.
  expect(
    SHARED_MESSAGE_SOURCES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER.some(
      hasCardListLine([1022]),
    ),
  ).toBe(true);
  expect(
    SHARED_MESSAGE_SOURCES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER.some(
      hasCardListLine([1058]),
    ),
  ).toBe(true);
}

/**
 * One entry in a shared sentence's provenance list: the program that declares it and where.
 *
 * Assumptions: this names the shape structurally rather than importing it, because the catalog
 * publishes the provenance maps as literals and their element type is inferred rather than exported.
 * A named interface is also what lets the predicate below document its parameter as one type instead
 * of enumerating the members, which is the form `jsdoc/require-param` accepts.
 */
interface SentenceProvenance {
  /** The declaring program's repository path. */
  readonly file: string;
  /** The lines within that program which declare or write the sentence. */
  readonly lines: readonly number[];
}

/**
 * Builds a predicate matching this program's entry in a shared sentence's provenance list.
 *
 * Assumptions: the match is on the file AND the lines together. A shared sentence is declared by
 * several programs at different lines, so matching the file alone would accept the wrong line and
 * matching the lines alone would accept the wrong program.
 * @param {readonly number[]} lines - The lines this program declares the sentence at.
 * @returns {(entry: SentenceProvenance) => boolean} A predicate true for the card-list program's own
 *   entry with exactly those lines.
 */
function hasCardListLine(lines: readonly number[]): (entry: SentenceProvenance) => boolean {
  /**
   * Tests one provenance entry.
   * @param {SentenceProvenance} entry - One entry from the sentence's provenance list.
   * @returns {boolean} Whether it is the card-list program's, at exactly the expected lines.
   */
  function matches(entry: SentenceProvenance): boolean {
    return entry.file.endsWith('COCRDLIC.cbl') && String(entry.lines) === String(lines);
  }
  return matches;
}

/**
 * Asserts each sentence keeps the exact spelling its own program declares.
 *
 * ⚠️ Assumptions: this program's sentences are UPPER CASE, and its siblings' are not. `COCRDSLC` and
 * `COCRDUPC` declare the mixed-case `PF03 pressed.Exiting` with trailing padding, while this program
 * declares `PF03 PRESSED.EXITING` at L120 with none. They are two different strings and neither is a
 * normalisation of the other -- transformation rule T8 carries every user-visible string across
 * character for character, so a case that compared them case-insensitively would bless whichever
 * screen had been "tidied".
 *
 * Alternatives Considered: retyping each sentence into this file as the expected value. Rejected
 * outright, and this is the failure mode it produces: a paraphrase in the screen plus the same
 * paraphrase in the test agree with each other, so the case passes green while the fidelity it exists
 * to protect is gone. Every assertion here is therefore a STRUCTURAL property of the catalogued
 * value -- its case, its padding, its punctuation, its distinctness -- and never a literal copy of it.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function preservesTheDeclaredSpellingOfEverySentence(): void {
  const uppercaseSentences: readonly string[] = [
    CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.text,
    CARD_LIST_STATUS.WS_EXIT_MESSAGE.text,
    CARD_LIST_STATUS.WS_NO_RECORDS_FOUND.text,
    CARD_LIST_STATUS.WS_MORE_THAN_1_ACTION.text,
    CARD_LIST_STATUS.WS_INVALID_ACTION_CODE.text,
    CARD_LIST_PAGING.NO_PREVIOUS_PAGES_TO_DISPLAY,
    CARD_LIST_PAGING.NO_MORE_PAGES_TO_DISPLAY,
    CARD_LIST_PAGING.NO_MORE_RECORDS_TO_SHOW,
  ];

  for (const sentence of uppercaseSentences) {
    // WHY : Assumptions: comparing a value against its own upper-casing proves it contains no
    //       lower-case letter, which is what this program's house style is, WITHOUT restating the
    //       sentence. A sibling screen's mixed-case form would fail this immediately.
    expect(sentence).toBe(sentence.toUpperCase());
  }

  // WHY : ⚠️ Assumptions: the exit sentence carries NO trailing padding here, unlike the sibling
  //       programs' padded mixed-case form. The declared width is metadata about the COBOL field
  //       (`PIC X(75)`) and is deliberately not baked into the transcription, so the text is shorter
  //       than the field that held it.
  expect(CARD_LIST_STATUS.WS_EXIT_MESSAGE.text).toBe(
    CARD_LIST_STATUS.WS_EXIT_MESSAGE.text.trimEnd(),
  );
  expect(CARD_LIST_STATUS.WS_EXIT_MESSAGE.text.length).toBeLessThan(
    CARD_LIST_STATUS.WS_EXIT_MESSAGE.declaredWidth,
  );

  // WHY : ⚠️ Assumptions: both filter refusals have NO space after their comma, exactly as
  //       `app/cbl/COCRDLIC.cbl` L1022 and L1058 write them. This is the detail most likely to be
  //       "corrected" by a reader, so it is asserted structurally rather than trusted.
  expect(SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER).toContain(',');
  expect(SHARED_MESSAGES.ACCOUNT_FILTER_IF_SUPPLIED_MUST_BE_A_11_DIGIT_NUMBER).not.toContain(', ');
  expect(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER).toContain(',');
  expect(SHARED_MESSAGES.CARD_ID_FILTER_IF_SUPPLIED_MUST_BE_A_16_DIGIT_NUMBER).not.toContain(', ');

  // WHY : Assumptions: the record-action prompt DOES take a space after its comma, so the property
  //       above belongs to the two filter refusals alone and is not a rule about this program's
  //       commas. Asserting the difference is what stops the previous two assertions being
  //       generalised into a change that would break L116.
  expect(CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.text).toContain(', ');

  // WHY : ⚠️ Assumptions: the two live empty-state sentences are DISTINCT and differ in punctuation --
  //       the search-condition one ends in a full stop and the end-of-records one does not. They
  //       describe different moments and merging them would delete one of them.
  expect(CARD_LIST_STATUS.WS_NO_RECORDS_FOUND.text.endsWith('.')).toBe(true);
  expect(CARD_LIST_PAGING.NO_MORE_RECORDS_TO_SHOW.endsWith('.')).toBe(false);
  expect(CARD_LIST_STATUS.WS_NO_RECORDS_FOUND.text).not.toBe(
    CARD_LIST_PAGING.NO_MORE_RECORDS_TO_SHOW,
  );

  // WHY : ⚠️ Assumptions: this program contributes EIGHT catalogued sentences and no more -- five
  //       `88`-level conditions and three run-time moves. A ninth candidate exists in the baseline and
  //       is deliberately absent: the `MOVE` at `app/cbl/COCRDLIC.cbl` L1243 is a COMMENT LINE, with
  //       `*` in column 7, so the sentence it names is unreachable dead code and the live statement is
  //       the `SET` at L1244 which selects the L122 sentence instead. Transcribing a sentence no
  //       statement can reach would put text in the catalog that no screen may ever paint, so the
  //       count is asserted here to keep that decision visible. A tenth reachable string, the composed
  //       file-error group at L153-L171, is likewise uncatalogued: it is a `PIC X` group assembled from
  //       filler and run-time codes rather than an `88`-level literal, so it has no single value to
  //       transcribe.
  expect(Object.keys(CARD_LIST_STATUS)).toHaveLength(5);
  expect(Object.keys(CARD_LIST_PAGING)).toHaveLength(3);
}

/**
 * Asserts the record-action prompt is painted on the row-22 line and not in the row-23 error field.
 *
 * Assumptions: the prompt names both action codes, which is where the selection column's domain comes
 * from -- `app/cbl/COCRDLIC.cbl` L116 tells the operator to type `S` for detail or `U` to update. The
 * screen exports those two characters as `CARD_LIST_ROW_ACTION_CODES`, and both are asserted to appear
 * in the sentence so the control's domain and the sentence describing it cannot drift apart.
 *
 * ⚠️ Refactoring Rationale: the CHANNEL is asserted, which it was not. The screen collapsed both of the
 * mapset's message fields onto the row-23 band and switched its severity between `info` and `error`, so
 * this advisory was painted as an informational alert inside the one field the reference reserves for
 * what an operator must act on. `1400-SETUP-MESSAGE` keeps them apart: it moves `WS-ERROR-MSG` into
 * `ERRMSGO` and, under its own separate guard, `WS-INFO-MSG` into `INFOMSGO` with `DFHNEUTR`
 * (`app/cbl/COCRDLIC.cbl` L924 to L929), and the two fields differ in colour, in width and in row --
 * `INFOMSG` is `COLOR=NEUTRAL` at `POS=(20,19)` with `LENGTH=45` and `ERRMSG` is `COLOR=RED` at
 * `POS=(23,1)` with `LENGTH=78` (`app/bms/COCRDLI.bms` L324 to L334). Locating the sentence in the
 * informational band proves the screen delegates that channel at all, since the frame renders that band
 * only for a screen that published one, and asserting the row-23 band does NOT carry it is what stops
 * the collapse being reintroduced.
 * @returns {Promise<void>} Resolves once the prompt has been located on its own line.
 */
async function paintsTheRecordActionPrompt(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  await waitFor(
    /**
     * Holds until the prompt has been painted on the row-22 line.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function promptPainted(): void {
      expectVerbatimMessageInInformationBand(CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.text);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '').toBe('');
  expect(CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.text).toContain(CARD_LIST_ROW_ACTION_CODES.detail);
  expect(CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.text).toContain(CARD_LIST_ROW_ACTION_CODES.update);
}

/**
 * Builds the rejection a service refusal reaches a screen as, carrying one problem document.
 *
 * Assumptions: the rejection is an `ApiRequestError` and NOT a bare problem document, because that is
 * the only shape the paging hook reads a sentence out of: `ui/src/hooks/usePagedQuery.ts` narrows a
 * rejection through `problemDocumentOf`, which returns `reason.problem` for that error type and `null`
 * for everything else. A case that rejected with the document itself would exercise the
 * no-document path while appearing to exercise the described one.
 * @param {number} status - The HTTP status the service answered with.
 * @param {string | null} message - The service's own sentence, or `null` when it sent none.
 * @returns {ApiRequestError} The rejection, carrying a complete problem document.
 */
function refusalCarrying(status: number, message: string | null): ApiRequestError {
  return new ApiRequestError('PROBLEM', status, apiError({ status, message }), 'PROBLEM');
}

/**
 * Mounts the browse against a read that REFUSES, and waits for the refusal to be reported.
 *
 * Assumptions: the wait is on the row-23 band rather than on the table, which is why this cannot reuse
 * {@link renderBrowse}. A refused opening read renders no table at all, so that helper's settled
 * condition would never hold and the case would time out instead of asserting.
 * @param {unknown} rejection - What the transport rejects with.
 * @param {string} expected - The sentence the band is expected to carry once the refusal is reported.
 * @returns {Promise<void>} Resolves once the band carries that sentence.
 */
async function renderRefusedBrowse(rejection: unknown, expected: string): Promise<void> {
  vi.mocked(listCards).mockRejectedValue(rejection);

  await renderInAppShell(<CardListScreen />, {
    initialEntries: ['/cards'],
    routePath: '/cards',
  });

  await waitFor(
    /**
     * Holds until the refusal has reached the row-23 line.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function refusalReported(): void {
      expectVerbatimMessageInBand(expected);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );
}

/**
 * Asserts a described refusal is reported in the service's own words, not as a momentary outage.
 *
 * Purpose
 * -------
 * The browse reported every failed read as one authored availability sentence, so a refusal an
 * operator could act on -- an authority refusal, a malformed filter, a payload the service would not
 * accept -- was described as a condition that clears on its own. The published contract rules that
 * reading out: `listCards` declares 400, 401, 403, 405, 406, 413 and 415 alongside 500, and declares
 * that a page which matched nothing answers 200 with an empty array "rather than 404, because a query
 * that matched nothing succeeded"
 * (`services/card-service/src/main/resources/openapi/card-api.yaml` L509 to L537). So a refusal here is
 * never absence, and the sentence the service sent is the only one that names what happened.
 *
 * Assumptions: the assertion is that the authored fallbacks are ABSENT as well as that the service's
 * sentence is present. Painting both would still be wrong -- the operator would be told to wait and to
 * act -- and only the absence catches that. ⚠️ Refactoring Rationale: BOTH authored sentences are named,
 * where one was: the single availability sentence this screen used for every bodiless failure has been
 * replaced by a classified pair, so asserting only one of them would leave the other free to appear
 * beside a sentence the service sent.
 * @returns {Promise<void>} Resolves once the described refusal has been reported verbatim.
 */
async function statesTheServiceSentenceForADescribedRefusal(): Promise<void> {
  /*
   * Assumptions: the sentence is invented HERE rather than drawn from the message catalog, and that is
   *   deliberate rather than an omission. It stands for a body the SERVICE composed, so it must not be
   *   one of this screen's own catalogued strings: if it were, the case would pass against a screen
   *   that ignored the document and painted a local string that happened to match.
   */
  const serviceSentence = 'Card browse is not permitted for this operator.';
  const forbidden = 403;

  await renderRefusedBrowse(refusalCarrying(forbidden, serviceSentence), serviceSentence);

  const band = screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '';
  expect(band).not.toContain(TRANSIENT_FAILURE_TRY_AGAIN);
  expect(band).not.toContain(PERSISTENT_FAILURE_REPORT_IT);
}

/**
 * Asserts a refusal carrying no sentence falls back to the PERSISTENT catalogued sentence.
 *
 * Purpose
 * -------
 * A rejection that never reached the service -- a dropped connection -- carries no problem document, so
 * `usePagedQuery` reports `isFailed` with `error` null and there is nothing to quote. That is the one
 * state an authored sentence belongs in, and this case fixes which authored sentence.
 *
 * ⚠️ Refactoring Rationale: the expected sentence was
 * `Card data is temporarily unavailable. Report it with the correlation id.` and is now the catalogue's
 * persistent one. That sentence asserted BOTH remedies at once -- wait, and report -- so it was wrong
 * whichever the failure actually was, and `ui/src/api/client.ts` names this screen in the measured
 * finding it records at `ApiFailureRemedy`: a timeout, a dropped connection and a 500 were
 * indistinguishable on every screen, and a 404 was presented as "temporarily unavailable" on `/cards`.
 * A screen that says one thing for every failure cannot be corrected by rewording it, which is why the
 * expectation moves to a PAIR of cases rather than to a different single sentence.
 *
 * ⚠️ Assumptions: a plain `Error` is expected to reach the PERSISTENT sentence and not the transient one,
 * which is the conservative direction and is deliberate. `isTransientFailure` narrows on the
 * `ApiRequestError` class, so a value whose provenance cannot be established is not classified at all --
 * and the client's own `remedyFor` argues the same way about the real thing: a `NETWORK` failure is not
 * transient, because a request that never resolved a host fails again identically and inviting a retry
 * for it sends the operator into a loop with no exit.
 *
 * Assumptions: the width is still asserted, because the band silently clips and a sentence whose tail is
 * cut carries no indication that it was.
 * @returns {Promise<void>} Resolves once the persistent sentence has been reported verbatim.
 */
async function statesTheCataloguedSentenceWhenTheRefusalCarriesNone(): Promise<void> {
  await renderRefusedBrowse(new Error('the connection was lost'), PERSISTENT_FAILURE_REPORT_IT);

  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '').not.toContain(
    TRANSIENT_FAILURE_TRY_AGAIN,
  );
  expect(PERSISTENT_FAILURE_REPORT_IT.length).toBeLessThanOrEqual(MESSAGE_BAND_CONTENT_WIDTH);
}

/**
 * Asserts a momentary outage carrying no sentence is reported as one to wait out, not to report.
 *
 * ⚠️ Assumptions: the refusal is built as a REAL `ApiRequestError` at a transient status, because that is
 * the only shape the classification reads. `ui/src/api/client.ts` derives `transient` from the kind and
 * the status -- a timeout, or 408, 429, 502, 503, 504 -- and exposes it through `isTransientFailure`,
 * which narrows on the class; a structurally similar object is deliberately not accepted, so a case
 * arranged from one would exercise the fallback while appearing to exercise this branch.
 *
 * ⚠️ Assumptions: the persistent sentence is asserted ABSENT as well. Painting both would tell the
 * operator to wait and to report the same failure, and only the absence catches that.
 * @returns {Promise<void>} Resolves once the outage sentence has been reported verbatim.
 */
async function statesTheOutageSentenceForATransientRefusal(): Promise<void> {
  const unavailable = 503;

  await renderRefusedBrowse(refusalCarrying(unavailable, null), TRANSIENT_FAILURE_TRY_AGAIN);

  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID).textContent ?? '').not.toContain(
    PERSISTENT_FAILURE_REPORT_IT,
  );
  expect(TRANSIENT_FAILURE_TRY_AGAIN.length).toBeLessThanOrEqual(MESSAGE_BAND_CONTENT_WIDTH);
}

/**
 * Asserts an empty result paints the search-condition sentence and no rows.
 *
 * Assumptions: the empty page is built with no rows, so `pageResponse` reports both cursors as `null`
 * -- its own contract, since a cursor identifies a row and an empty page has none. That is what makes
 * this the state the baseline reaches at L1241-L1244, where the screen ordinal is one and the row
 * counter is zero.
 * @returns {Promise<void>} Resolves once the sentence has been painted and the rows counted.
 */
async function paintsTheSearchConditionSentenceWhenNothingMatches(): Promise<void> {
  await renderBrowse([]);

  await waitFor(
    /**
     * Holds until the empty-result sentence has been painted.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function emptySentencePainted(): void {
      expectVerbatimMessage(CARD_LIST_STATUS.WS_NO_RECORDS_FOUND.text);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  // WHY : Assumptions: the ABSENCE of row controls is what is asserted, rather than a body-row count
  //       of zero. The design system paints a placeholder row in an empty table, so a row count would
  //       be one and would say nothing about the data; an action entry exists only for a delivered
  //       row, so counting those distinguishes an empty page from a populated one exactly.
  expect(rowActionEntries()).toHaveLength(0);
}

/**
 * Asserts exactly the four measured attention identifiers reach a handler, and no others.
 *
 * Assumptions: the measured set is `CCARD-AID-ENTER`, `CCARD-AID-PFK03`, `CCARD-AID-PFK07` and
 * `CCARD-AID-PFK08` -- the four this program tests at `app/cbl/COCRDLIC.cbl` L371-L374 before doing
 * anything else. Three of them are advertised in the legend and the fourth is not, which is why the
 * count of legend controls is three rather than four.
 *
 * ⚠️ Assumptions: Enter is BOUND but UNADVERTISED. `app/bms/COCRDLI.bms` L339 paints only the three
 * function keys on row 24, so the screen gives its Enter handler an empty label and
 * `ui/src/layout/PfKeyBar.tsx` renders no control for it -- while the key still submits the turn. A
 * case that inferred the binding set from the legend alone would conclude Enter was unbound.
 * @returns {Promise<void>} Resolves once the legend has been counted and Enter shown to be live.
 */
async function bindsTheFourMeasuredIdentifiersAndNoOthers(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);

  const descriptors = advertisedKeyDescriptors();
  expect(descriptors).toHaveLength(3);

  // WHY : Assumptions: the paging descriptors come from `UNIFORM_PF_KEY_LABELS`, the shared set two of
  //       the three take, so they are compared against the imported values rather than retyped. The
  //       exit descriptor is private to the screen module, so it is asserted by SHAPE -- it names the
  //       third function key -- which is as much as this file can check without duplicating a
  //       constant the screen deliberately keeps to itself.
  expect(descriptors[1]).toBe(UNIFORM_PF_KEY_LABELS.PFK07);
  expect(descriptors[2]).toBe(UNIFORM_PF_KEY_LABELS.PFK08);
  expect(descriptors[0]?.startsWith('F3=')).toBe(true);

  // WHY : Assumptions: no legend control advertises Enter, which is the observable half of the
  //       omission recorded above. The baseline does not paint it and neither does the target.
  for (const descriptor of descriptors) {
    expect(descriptor.toLowerCase()).not.toContain('enter');
  }

  // WHY : Assumptions: Enter is shown to be live by the re-read it causes. `app/cbl/COCRDLIC.cbl`
  //       L989-L996 edits the filters and the selection array on the Enter turn and the main
  //       `EVALUATE` then re-reads at L545, so a second browse read is exactly what the binding does.
  await pressPfKey(user, 'ENTER');
  await waitFor(
    /**
     * Holds until the Enter turn has issued its re-read.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function enterTurnRead(): void {
      expect(readCount()).toBe(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );
}

/**
 * Asserts the forward step reaches the same handler from the keyboard and from the legend control.
 *
 * Alternatives Considered: asserting only the legend click, which is the cheaper of the two and reads
 * as though it covered the feature. Rejected because the 3270 original was keyboard-only, so the
 * KEYBOARD path is the contract and the bar is an addition made for discoverability: a case that only
 * clicked would pass in full while every keyboard binding in the application was broken. The converse
 * also holds -- a case that only typed would pass while the bar dispatched nothing -- so both paths
 * are driven and both are required to produce the same read.
 * @returns {Promise<void>} Resolves once both paths have issued a forward read.
 */
async function dispatchesTheForwardStepFromKeyboardAndControl(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);

  await pressPfKey(user, 'PFK08');
  await waitFor(
    /**
     * Holds until the keyboard path has read.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function keyboardStepRead(): void {
      expect(readCount()).toBe(2);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );
  expect(recordedQuery(1).direction).toBe(FORWARD_DIRECTION);

  await clickLegendKey(user, UNIFORM_PF_KEY_LABELS.PFK08);
  await waitFor(
    /**
     * Holds until the pointer path has read.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function pointerStepRead(): void {
      expect(readCount()).toBe(3);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );
  expect(recordedQuery(2).direction).toBe(FORWARD_DIRECTION);
}

/**
 * Builds a page read whose settlement this case controls.
 *
 * Assumptions: a hand-rolled deferred rather than a timer, because the property under test is the state
 * of the screen WHILE a page turn is outstanding, and a timer would make the window a duration to race
 * against instead of a state to observe.
 * @returns {{ promise: Promise<PageResponse<CardSummary>>; settle: () => void }} The promise to hand the
 *   mocked client, and the function that settles it with one full page.
 */
function deferredPage(): { promise: Promise<PageResponse<CardSummary>>; settle: () => void } {
  /*
   * Assumptions: the captured resolver is held as possibly-undefined rather than seeded with a throwing
   *   placeholder, because a placeholder is itself a function and every function in this file owes a doc
   *   comment -- so seeding it would document a branch the executor makes unreachable.
   */
  let resolvePage: ((page: PageResponse<CardSummary>) => void) | undefined;
  const promise = new Promise<PageResponse<CardSummary>>(
    /**
     * Captures the resolver so the case can settle the read when it chooses.
     * @param {(page: PageResponse<CardSummary>) => void} resolve - The promise's own resolver.
     * @returns {void} Nothing; the resolver is captured as a side effect.
     */
    (resolve: (page: PageResponse<CardSummary>) => void): void => {
      resolvePage = resolve;
    },
  );
  return {
    promise,
    /**
     * Settles the held read with one full page.
     * @returns {void} Nothing; the settlement is the effect.
     * @throws {Error} If called before the promise executor has run, which cannot happen for a native
     *   promise but is stated rather than assumed.
     */
    settle: (): void => {
      if (resolvePage === undefined) {
        throw new Error('the deferred page was settled before it was armed');
      }
      resolvePage(pageResponse(FULL_PAGE_ROWS, { hasNext: true }));
    },
  };
}

/**
 * Asserts the outstanding page turn is announced through a live region that is empty when idle.
 *
 * ⚠️ Assumptions: the region's presence and EMPTINESS are asserted BEFORE the turn is taken, and that
 * ordering is the point. `ui/src/layout/fieldHelp.tsx` records that a live region has to be in the
 * accessibility tree before its content changes for the change to be announced, so a region rendered
 * only while busy would arrive with its sentence already in place and be read by nothing -- which loses
 * the transition that matters. A case that only looked for the sentence would pass against exactly that.
 *
 * ⚠️ Assumptions: the SAME DOM node is asserted across all three states, by identity, because "mounted
 * throughout" is the property under test and a remounted region is indistinguishable from a live one by
 * text alone.
 *
 * Assumptions: the sentence is compared by identity against the catalogue's authored `REQUEST_IN_PROGRESS`
 * rather than against a literal, so a reword in the catalogue moves this case with it.
 * @returns {Promise<void>} Resolves once all three states have been observed.
 */
async function announcesTheOutstandingPageTurn(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);
  const idle = screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID);
  expect(idle).toHaveTextContent('');

  const held = deferredPage();
  vi.mocked(listCards).mockReturnValue(held.promise);
  await pressPfKey(user, 'PFK08');

  const busy = screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID);
  expect(busy).toBe(idle);
  expect(busy).toHaveTextContent(REQUEST_IN_PROGRESS);

  await act(
    /**
     * Lets the held read settle inside the scope, so the screen's own update is flushed.
     * @returns {Promise<void>} Resolves once the read has settled.
     */
    async (): Promise<void> => {
      held.settle();
      await held.promise;
    },
  );

  await waitFor(
    /**
     * Holds until the announcement has been withdrawn.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function announcementWithdrawn(): void {
      const settled = screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID);
      expect(settled).toBe(idle);
      expect(settled).toHaveTextContent('');
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );
}

/**
 * Asserts only the key whose own turn is outstanding is declined, and the other keys stay live.
 *
 * ⚠️ Assumptions: the decline is asserted PER KEY. One busy flag for the browse as a whole would decline
 * the BACKWARD key because the forward one is waiting, which withdraws a key at a moment the reference
 * does not: a 3270 inhibits the keyboard for the duration of a turn and then releases every key at once,
 * never one key because of another. So the forward key is asserted busy and declining, while the
 * backward key and the exit key are asserted NOT busy.
 *
 * ⚠️ Assumptions: the declined control is asserted to stay PRESENT, ENABLED and NAMED. A disabled control
 * leaves the focus order, so an operator tabbing the legend would find the set of controls changing
 * under them mid-turn; the contract is that a busy key is a valid key pressed early, so it stays where
 * it was. The exit key carries no `aria-busy` attribute at all, which is the renderer's way of saying
 * this key never reports busy -- distinct from reporting `false`.
 *
 * Assumptions: no invalid-key sentence appears. The decline is silent, and this program has no
 * invalid-key message to show in any case -- its own `WHEN OTHER` arm coerces the key
 * (`app/cbl/COCRDLIC.cbl` L370-L380).
 * @returns {Promise<void>} Resolves once the decline and the surviving keys have been observed.
 */
async function declinesOnlyTheKeyWhoseTurnIsOutstanding(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);
  const settledReads = readCount();

  const held = deferredPage();
  vi.mocked(listCards).mockReturnValue(held.promise);
  await pressPfKey(user, 'PFK08');
  expect(readCount()).toBe(settledReads + 1);

  await pressPfKey(user, 'PFK08');
  await clickLegendKey(user, UNIFORM_PF_KEY_LABELS.PFK08);
  expect(readCount()).toBe(settledReads + 1);

  const forward = within(keyLegend()).getByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK08 });
  const backward = within(keyLegend()).getByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK07 });
  /*
   * Assumptions: the exit control is located through the legend's own advertised descriptors rather than
   *   through a constant, because the exit label is private to the screen module -- the case that counts
   *   the bindings makes the same accommodation for the same reason, asserting its SHAPE instead.
   */
  const exitDescriptor = advertisedKeyDescriptors()[0] ?? '';
  expect(exitDescriptor.startsWith('F3=')).toBe(true);
  const exit = within(keyLegend()).getByRole('button', { name: exitDescriptor });
  expect(forward).toHaveAttribute('aria-busy', 'true');
  expect(forward).toBeEnabled();
  expect(forward).toHaveAccessibleName();
  expect(backward).toHaveAttribute('aria-busy', 'false');
  expect(backward).toBeEnabled();
  expect(exit).not.toHaveAttribute('aria-busy');
  expect(exit).toBeEnabled();

  await act(
    /**
     * Settles the held read so the screen leaves its outstanding state inside an act scope.
     * @returns {Promise<void>} Resolves once the read has settled.
     */
    async (): Promise<void> => {
      held.settle();
      await held.promise;
    },
  );
}

/**
 * Asserts the backward refusal is reported identically from the keyboard and from the legend control.
 *
 * Assumptions: the first page is the state under test, so both paths are refused rather than served.
 * That makes the two paths comparable on the sentence they produce, which is the observable the
 * baseline specifies at L901-L903, rather than on a read neither of them issues.
 * @returns {Promise<void>} Resolves once both paths have produced the refusal without reading.
 */
async function reportsTheBackwardRefusalFromKeyboardAndControl(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);

  await pressPfKey(user, 'PFK07');
  await waitFor(
    /**
     * Holds until the keyboard path has painted the refusal.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function keyboardRefusalPainted(): void {
      expectVerbatimMessage(CARD_LIST_PAGING.NO_PREVIOUS_PAGES_TO_DISPLAY);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  await clickLegendKey(user, UNIFORM_PF_KEY_LABELS.PFK07);
  await waitFor(
    /**
     * Holds until the pointer path has painted the same refusal.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function pointerRefusalPainted(): void {
      expectVerbatimMessage(CARD_LIST_PAGING.NO_PREVIOUS_PAGES_TO_DISPLAY);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(readCount()).toBe(1);
}

/**
 * Locates the account-number filter control by the label the mapset gives it.
 *
 * Assumptions: the label text is normalised the way the two width cases already normalise it, because
 * the rendered label carries the source's own spacing and Testing Library matches the accessible name.
 * @returns {HTMLElement} The account-number entry control.
 * @throws {Error} If no control carries that name, which Testing Library raises.
 */
function accountFilterControl(): HTMLElement {
  return screen.getByLabelText(CARD_LIST_LABELS.accountNumberFilter.replace(/\s+/gu, ' ').trim());
}

/**
 * Types one account narrowing into the filter control and applies it.
 *
 * Assumptions: the narrowing is APPLIED through the screen's own control rather than by pressing
 * Enter, because both arms reach the same edit -- `2200-EDIT-INPUTS` is performed from the Enter arm
 * at `app/cbl/COCRDLIC.cbl` L989 to L996 -- and the control is the arm a pointer user has. The other
 * arm is exercised by the attention-identifier cases.
 * @param {HarnessOperator} user - The operator driving the document.
 * @param {string} narrowing - The eleven-digit account identifier to narrow by.
 * @returns {Promise<void>} Resolves once the narrowed read has been dispatched.
 */
async function applyAccountNarrowing(user: HarnessOperator, narrowing: string): Promise<void> {
  await user.type(accountFilterControl(), narrowing);
  await user.click(screen.getByRole('button', { name: CARD_LIST_ENTRY_CONTROL_LABELS.filter }));

  await waitFor(
    /**
     * Holds until a read carrying the narrowing has been dispatched.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function narrowedReadDispatched(): void {
      expect(recordedQuery(readCount() - 1).accountId).toBe(narrowing);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );
}

/**
 * Asserts every paging step carries the narrowing in force, not only the turn that applied it.
 *
 * Purpose
 * -------
 * This is the case for the finding that the forward key dropped the account narrowing: the request
 * carried only the cursor and the direction while the entry box still visibly held what the operator
 * typed, so the grid repopulated with rows from other accounts under an unchanged filter.
 *
 * ⚠️ Assumptions: the narrowing belongs on the paging request because it is what the browse is a browse
 * OF. `app/cbl/COCRDLIC.cbl` reads every page through `9000-READ-FORWARD`, which filters each record it
 * reads against the carried `CDEMO-ACCT-ID` -- `9500-FILTER-RECORDS` at L1281 to L1310 rejects a record
 * whose account does not match -- and PF8 performs that same paragraph (L456 to L470). There is no arm
 * in which a page is read unfiltered while a filter is in force, so a request omitting it describes a
 * browse the reference cannot perform.
 *
 * Assumptions: BOTH halves are asserted -- the request carries the narrowing AND the entry box still
 * shows it. The finding is precisely the disagreement between the two, so an assertion on either alone
 * would pass against the defect stated the other way round.
 * @returns {Promise<void>} Resolves once the forward step has been observed to carry the narrowing.
 */
async function carriesTheNarrowingIntoEveryPagingStep(): Promise<void> {
  const narrowing = FIRST_ROW.accountId;
  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);

  await applyAccountNarrowing(user, narrowing);
  const afterFilter = readCount();

  await clickLegendKey(user, UNIFORM_PF_KEY_LABELS.PFK08);

  await waitFor(
    /**
     * Holds until the forward step has been dispatched.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function forwardStepDispatched(): void {
      expect(readCount()).toBeGreaterThan(afterFilter);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  const stepped = recordedQuery(readCount() - 1);
  expect(stepped.accountId).toBe(narrowing);
  expect(stepped.cursor).toBe(TRAILING_CURSOR);
  expect(stepped.direction).toBe('next');
  expect((accountFilterControl() as HTMLInputElement).value).toBe(narrowing);
}

/**
 * Asserts neither paging control is ever withdrawn, at either boundary.
 *
 * ⚠️ Assumptions: a boundary is ANNOUNCED and never expressed by taking the key away. The reference
 * paints `ENTER=Continue  F3=Exit  F7=Backward  F8=Forward` from one unconditional legend field
 * (`app/bms/COCRDLI.bms` L336 to L341 declares `ATTRB=(ASKIP,NORM)` with that INITIAL and no paragraph
 * darkens it), and its boundary arms move a SENTENCE into the message field instead -- L901 to L904 for
 * the backward boundary and L905 to L909 for the forward one -- while still re-sending the map. So a
 * disabled control would remove a sentence the operator reads, which is the opposite of the accessible
 * improvement it looks like.
 *
 * Assumptions: both boundaries are exercised in one case because they are one property of one control
 * group. The opening page is the backward boundary by construction, and a page reporting no further one
 * is the forward boundary, so a single page with `hasNext` false is at both at once.
 * @returns {Promise<void>} Resolves once both keys have been shown enabled at both boundaries.
 */
async function withdrawsNoPagingKeyAtEitherBoundary(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS);

  for (const descriptor of [UNIFORM_PF_KEY_LABELS.PFK07, UNIFORM_PF_KEY_LABELS.PFK08]) {
    expect(within(keyLegend()).getByRole('button', { name: descriptor })).toBeEnabled();
  }

  await pressPfKey(user, 'PFK07');
  await waitFor(
    /**
     * Holds until the backward boundary has announced itself.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function backwardBoundaryAnnounced(): void {
      expectVerbatimMessageInBand(CARD_LIST_PAGING.NO_PREVIOUS_PAGES_TO_DISPLAY);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  await pressPfKey(user, 'PFK08');
  await waitFor(
    /**
     * Holds until the forward boundary has announced itself.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function forwardBoundaryAnnounced(): void {
      expectVerbatimMessageInBand(CARD_LIST_PAGING.NO_MORE_RECORDS_TO_SHOW);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  // WHY : Assumptions: enablement is re-read AFTER both refusals, because a screen that greyed a key on
  //       reaching its boundary would still have satisfied the assertion above -- the sentence and the
  //       withdrawal are not alternatives, and the finding this guards against is the withdrawal.
  for (const descriptor of [UNIFORM_PF_KEY_LABELS.PFK07, UNIFORM_PF_KEY_LABELS.PFK08]) {
    expect(within(keyLegend()).getByRole('button', { name: descriptor })).toBeEnabled();
  }

  // WHY : Assumptions: no read was issued past either boundary, which is the other half of the
  //       reference's behaviour -- its boundary arms state a sentence and re-send the map WITHOUT
  //       performing the read paragraph, so a request here would be a page nobody asked for.
  expect(readCount()).toBe(1);
}

/**
 * Asserts the page size is the mapset's row count and never reaches the wire.
 *
 * Assumptions: seven is asserted against the screen's own exported constant, whose provenance the
 * arity case establishes from two independent artifacts, and the ABSENCE is asserted against every
 * recorded request. `card-api.yaml` declares no page-size member on the browse query at all, so a
 * request carrying one would be describing a body the service does not accept -- and the row count is
 * a property of a 24-row terminal frame rather than something a client negotiates.
 * @returns {Promise<void>} Resolves once the size has been checked and every request searched.
 */
async function keepsThePageSizeOffTheWire(): Promise<void> {
  expect(CARD_LIST_PAGE_SIZE).toBe(DECLARED_ROW_ARITY);

  const { user } = await renderBrowse(FULL_PAGE_ROWS, true);
  await clickLegendKey(user, UNIFORM_PF_KEY_LABELS.PFK08);

  await waitFor(
    /**
     * Holds until the forward step has been dispatched.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function forwardStepDispatched(): void {
      expect(readCount()).toBeGreaterThan(1);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  for (let index = 0; index < readCount(); index += 1) {
    for (const member of Object.keys(recordedQuery(index))) {
      expect(isPermittedQueryMember(member)).toBe(true);
    }
  }
}

/**
 * The address the browse occupies, spelled as `ui/src/router.tsx` declares it.
 *
 * Assumptions: this is the string the screen's own hand-over carries as its origin, so the two cases
 * below start the router at the same address the application does rather than at an approximation.
 */
const BROWSE_ADDRESS = '/cards';

/**
 * Mounts the browse and the update screen under one router, so a transfer and its return are real.
 *
 * Purpose
 * -------
 * The narrowing an operator applied has to SURVIVE a transfer out and the exit key coming back, and
 * nothing about that is observable inside one screen: the browse unmounts on the way out and mounts
 * again on the way back, so the property under test is what travels between two mounts. Mounting both
 * screens under one memory router is the only arrangement in which that is a real transition rather
 * than an assertion about an argument.
 *
 * Assumptions: the screens are mounted as CHILDREN of a pathless layout route holding the shell, which
 * is how `ui/src/router.tsx` declares them and what `ui/src/test/setup.ts` records as the arrangement to
 * reproduce -- passing a screen as the shell's `children` replaces the outlet instead of filling it, so
 * an outlet regression would leave such a test passing.
 *
 * Assumptions: the theme provider is the application's own `cardDemoTheme`, imported rather than
 * restated, for the reason the shared harness gives -- a subject rendered without it renders Ant
 * Design's defaults rather than the BMS bridge.
 *
 * Alternatives Considered: extending the shared `renderInAppShell` helper to accept a history entry
 * carrying state. Rejected because `ui/src/test/setup.ts` belongs to another group in this checkpoint
 * and its `initialEntries` is typed as strings; a local harness reaches the same arrangement without
 * editing a file this group does not own.
 * @param {string | { pathname: string; state: { accountId: string } }} entry - The history entry to
 *   open at, either a bare address or one carrying a hand-over.
 * @returns {HarnessOperator} The operator bound to the rendered document.
 */
function renderCardRoutes(
  entry: string | { pathname: string; state: { accountId: string } },
): HarnessOperator {
  const user = userEvent.setup();

  render(
    <ConfigProvider theme={cardDemoTheme}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route element={<AppShell />}>
            <Route path={BROWSE_ADDRESS} element={<CardListScreen />} />
            <Route path={CARD_EDIT_ROUTE} element={<CardUpdateScreen />} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ConfigProvider>,
  );

  return user;
}

/**
 * Asserts an arrival carrying a narrowing opens narrowed, in the box and on the wire.
 *
 * Purpose
 * -------
 * This is the arrival half of the finding that the exit key from a card returned an operator to an
 * unnarrowed browse with both boxes empty. The reference repaints the filter field from the carried
 * `CDEMO-ACCT-ID` on every entry that is not a fresh one from the menu -- the `WHEN OTHER` arm of
 * `app/cbl/COCRDLIC.cbl` L849 to L854 moves the carried identifier into `ACCTSIDO` and sets the field's
 * modified-data tag -- and the browse it then reads is narrowed by that same field.
 *
 * Assumptions: both the BOX and the REQUEST are asserted. A screen that seeded only the box would show
 * the operator a filter over rows from every account, and one that seeded only the request would narrow
 * the rows beside an empty box; the reference can exhibit neither, because one carried field feeds both.
 * @returns {Promise<void>} Resolves once the narrowed arrival has been observed.
 */
async function adoptsTheNarrowingAnArrivalCarries(): Promise<void> {
  const narrowing = FIRST_ROW.accountId;
  answerWithPage(FULL_PAGE_ROWS, false);

  renderCardRoutes({ pathname: BROWSE_ADDRESS, state: { accountId: narrowing } });

  await waitFor(
    /**
     * Holds until the opening read has been dispatched.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function openingReadDispatched(): void {
      expect(readCount()).toBeGreaterThan(0);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(recordedQuery(0).accountId).toBe(narrowing);
  expect(recordedQuery(0).cursor).toBeUndefined();
  expect((accountFilterControl() as HTMLInputElement).value).toBe(narrowing);
}

/**
 * Asserts the exit key from the update screen returns to the browse still narrowed.
 *
 * Purpose
 * -------
 * This is the finding's own reproduction, end to end: narrow the browse, open a card's update form from
 * a row, press the exit key, and read what the browse comes back as. It failed because the transfer
 * carried the origin and not the narrowing, so the return was a first arrival.
 *
 * ⚠️ Assumptions: the narrowing travels because the reference's COMMAREA does. The transfer arm writes
 * it in before the transfer (`MOVE CC-ACCT-ID TO CDEMO-ACCT-ID` at `app/cbl/COCRDLIC.cbl` L1027, with
 * the update arm's transfer at L548 to L562), the update program receives that same area and never
 * writes that field, and its exit arm hands the area back (`app/cbl/COCRDUPC.cbl` L442 to L460). So the
 * operator returns to the browse they left rather than to the browse's opening state.
 *
 * Assumptions: the update screen's read is REFUSED rather than answered, because this case is about the
 * exit key and the record is not on the screen it asserts about. The refusal keeps that screen mounted
 * with its unconditional `F3=Exit` painted (`app/bms/COCRDUP.bms` L158 to L162), which is the control
 * being pressed, and it spares this file a card-detail fixture it would otherwise have to carry for a
 * screen it does not test.
 * @returns {Promise<void>} Resolves once the browse has come back narrowed.
 */
async function returnsToTheNarrowedBrowseFromTheUpdateScreen(): Promise<void> {
  const narrowing = FIRST_ROW.accountId;
  answerWithPage(FULL_PAGE_ROWS, false);
  vi.mocked(getCard).mockRejectedValue(new Error('the read was refused'));

  const user = renderCardRoutes(BROWSE_ADDRESS);

  await waitFor(
    /**
     * Holds until the opening page has been rendered.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function openingPageRendered(): void {
      expect(browseTable()).toBeInTheDocument();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  await applyAccountNarrowing(user, narrowing);

  // WHY : Assumptions: the transfer is driven as a TURN -- the update code typed into the first row's
  //       action field and then Enter -- because that is the reference's own path to the update
  //       program: `2250-EDIT-ARRAY` reads the row's character and the `EVALUATE` arm at
  //       `app/cbl/COCRDLIC.cbl` L548 to L562 transfers. Driving it through the row control would
  //       exercise the same hand-over by the target-only pointer path instead.
  await user.type(rowActionEntry(0), CARD_LIST_ROW_ACTION_CODES.update);
  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Holds until the update screen has replaced the browse.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function updateScreenMounted(): void {
      expect(screen.queryByRole('table')).toBeNull();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  const readsBeforeReturn = readCount();
  await pressPfKey(user, 'PFK03');

  await waitFor(
    /**
     * Holds until the browse has been re-read on the way back.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function browseReturned(): void {
      expect(readCount()).toBeGreaterThan(readsBeforeReturn);
      expect(browseTable()).toBeInTheDocument();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(recordedQuery(readCount() - 1).accountId).toBe(narrowing);
  expect((accountFilterControl() as HTMLInputElement).value).toBe(narrowing);
}

/**
 * Asserts the exit key leaves the browse.
 *
 * Assumptions: the destination is the main menu, which is where the baseline transfers on PF3 --
 * `app/cbl/COCRDLIC.cbl` L390-L399 moves `LIT-MENUPGM`, the menu program name, into the next-program
 * field and L402 issues the transfer. Transformation rule T5 turns that `EXEC CICS XCTL` into a
 * client-side route change, so the observable here is that the browse is no longer mounted.
 *
 * Assumptions: departure is asserted rather than the arriving screen, because the harness mounts ONE
 * route pattern -- `/cards` -- so the menu has no component to render into and its absence is not
 * evidence of anything. What the pattern does establish is that the address left `/cards`, since the
 * browse unmounts only when the route no longer matches.
 * @returns {Promise<void>} Resolves once the browse has been shown to have unmounted.
 */
async function leavesTheBrowseOnTheExitKey(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS);

  await pressPfKey(user, 'PFK03');

  await waitFor(
    /**
     * Holds until the browse has unmounted.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function browseUnmounted(): void {
      expect(screen.queryByRole('table')).toBeNull();
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );
}

/**
 * Asserts two selections are refused with the baseline's sentence and nothing is opened.
 *
 * Assumptions: this is a real constraint rather than a courtesy. The baseline can carry exactly one
 * selected record forward, so `app/cbl/COCRDLIC.cbl` L124 declares the refusal and the edit that
 * raises it counts the marked rows before acting. Both halves are asserted -- the sentence appears AND
 * the browse is still mounted -- because a screen that navigated anyway while also painting the
 * sentence would satisfy a message-only assertion.
 * @returns {Promise<void>} Resolves once the refusal has been shown and the browse shown to remain.
 */
async function refusesTwoSelectionsWithoutOpeningEither(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS);

  await user.type(rowActionEntry(0), CARD_LIST_ROW_ACTION_CODES.detail);
  await user.type(rowActionEntry(1), CARD_LIST_ROW_ACTION_CODES.update);
  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Holds until the refusal has been painted.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function refusalPainted(): void {
      expectVerbatimMessageInBand(CARD_LIST_STATUS.WS_MORE_THAN_1_ACTION.text);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  // WHY : Assumptions: the browse is still mounted, which is what "does not navigate" looks like here
  //       for the reason recorded on the exit case -- a route change unmounts it.
  expect(screen.queryByRole('table')).not.toBeNull();
}

/**
 * Asserts an unrecognised action character is refused with the baseline's own sentence.
 *
 * ⚠️ Assumptions: this sentence is about the SELECTION CHARACTER and is not an invalid-key message.
 * The two are routinely conflated and this program has only the former: measured by exhaustive search,
 * `COCRDLIC` never moves the shared `CCDA-MSG-INVALID-KEY` constant at all -- it is one of six
 * programs that do not -- and instead declares its own `INVALID ACTION CODE` at L126 for a character
 * the operator typed into a row. Its unrecognised-KEY path does something different again: L370-L380
 * sets an invalid flag and then `SET CCARD-AID-ENTER TO TRUE`, coercing the key into the Enter turn
 * with no message at all. So no case in this file asserts an invalid-key sentence, because the program
 * emits none.
 * @returns {Promise<void>} Resolves once the refusal has been shown and the browse shown to remain.
 */
async function refusesAnUnrecognisedActionCharacter(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS);

  // WHY : Assumptions: the character is chosen as one the declared domain excludes. The domain is the
  //       two codes `CARD_LIST_ROW_ACTION_CODES` publishes, from L116, and this assertion states that
  //       exclusion rather than relying on a reader knowing it.
  const unrecognised = 'X';
  expect(Object.values(CARD_LIST_ROW_ACTION_CODES)).not.toContain(unrecognised);

  await user.type(rowActionEntry(0), unrecognised);
  await pressPfKey(user, 'ENTER');

  await waitFor(
    /**
     * Holds until the refusal has been painted.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function refusalPainted(): void {
      expectVerbatimMessageInBand(CARD_LIST_STATUS.WS_INVALID_ACTION_CODE.text);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(screen.queryByRole('table')).not.toBeNull();
}

/**
 * Asserts each action code selects its own destination, and that neither destination carries a number.
 *
 * Assumptions: the reducer is exercised directly as well as through the DOM, because it is the exported
 * pure statement of the rule -- `reduceCardListSelection` returns the chosen row and action for a set
 * of entries -- and asserting it there fixes the mapping from character to action without depending on
 * a render. The DOM half then shows the browse acts on it.
 *
 * ⚠️ Assumptions: the destination is addressed by the row's OPAQUE SELECTOR and never by its card
 * number. `ui/src/routes/cards.ts` publishes `cardDetailPath` and `cardEditPath` over a selector for
 * exactly that reason, and this case asserts the built paths contain the selector and do NOT contain
 * the row's rendered number -- so a primary account number cannot reach a browser address bar, a
 * history entry or a server log. The two patterns are `/cards/:cardKey` and `/cards/:cardKey/edit`.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function selectsTheDestinationForEachActionCode(): void {
  const detailChoice = reduceCardListSelection([CARD_LIST_ROW_ACTION_CODES.detail, '', '']);
  expect(detailChoice.selectedRow).toBe(0);
  expect(detailChoice.action).toBe(CARD_LIST_ROW_ACTION_CODES.detail);
  expect(detailChoice.message).toBeNull();

  const updateChoice = reduceCardListSelection(['', CARD_LIST_ROW_ACTION_CODES.update, '']);
  expect(updateChoice.selectedRow).toBe(1);
  expect(updateChoice.action).toBe(CARD_LIST_ROW_ACTION_CODES.update);
  expect(updateChoice.message).toBeNull();

  // WHY : Assumptions: two marked rows produce the L124 refusal from the reducer itself, so the rule is
  //       fixed in the pure layer as well as in the rendered one.
  const refused = reduceCardListSelection([
    CARD_LIST_ROW_ACTION_CODES.detail,
    CARD_LIST_ROW_ACTION_CODES.update,
    '',
  ]);
  expect(refused.message).toBe(CARD_LIST_STATUS.WS_MORE_THAN_1_ACTION.text);
  expect(refused.action).toBeNull();

  const detailPath = cardDetailPath(FIRST_ROW.key);
  const editPath = cardEditPath(SECOND_ROW.key);
  expect(detailPath).toContain(FIRST_ROW.key);
  expect(editPath).toContain(SECOND_ROW.key);
  expect(editPath.endsWith('/edit')).toBe(true);
  expect(detailPath).not.toContain(FIRST_ROW.displayCardNumber);
  expect(editPath).not.toContain(SECOND_ROW.displayCardNumber);
}

/**
 * Asserts every rendered card number is masked to its last four digits.
 *
 * ⚠️ Assumptions: this is a LIST screen, so every row is masked without exception. The one endpoint in
 * the whole system that returns an unmasked number is the administrative card-detail read, and this is
 * not it -- `ui/src/api/cards.ts` publishes that as a separate operation which the browse never calls.
 *
 * Assumptions: the absence is asserted over the whole document as well as per row, because a leak need
 * not appear in the column it belongs to. A sixteen-digit run anywhere in the rendered text would be a
 * full number regardless of which element carried it, so the search is for the SHAPE rather than for a
 * particular value.
 * @returns {Promise<void>} Resolves once every row has been checked and the document searched.
 */
async function masksEveryRenderedCardNumber(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  for (const row of FULL_PAGE_ROWS) {
    const rendered = screen.getByText(row.displayCardNumber);
    expect(rendered).toBeInTheDocument();

    // WHY : Assumptions: the shape is a run of mask characters closed by exactly four digits, which is
    //       what "masked to the last four" means when written as a pattern. Asserting the pattern
    //       rather than the fixture's own string is what makes this a property of the RENDERING.
    expect(rendered.textContent ?? '').toMatch(/^\*+\d{4}$/u);
  }

  // WHY : Assumptions: sixteen is the declared width of a card number -- `app/cpy/CVACT02Y.cpy`
  //       declares `CARD-NUM PIC X(16)` and `app/cpy-bms/COCRDLI.CPY` L90 renders it as `CRDNUM1I PIC
  //       X(16)` -- so an unbroken run of sixteen digits is the signature of an unmasked one.
  expect(renderedText()).not.toMatch(/\d{16}/u);
}

/**
 * Asserts a browse row carries no member beyond the four the contract publishes.
 *
 * Assumptions: the closed member set is the mechanism that keeps the sensitive attributes off this
 * screen entirely. `ui/src/api/types.ts` declares `CardSummary` with exactly `key`,
 * `displayCardNumber`, `accountId` and `activeStatus`; the card verification value has no member in
 * that shape at all, so there is nothing for the browse to render, mask or accidentally log. Asserting
 * the set rather than the absence of one named field is what also catches a fifth member nobody
 * thought to forbid.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function exposesNoMemberBeyondThePublishedFour(): void {
  expect(Object.keys(FIRST_ROW).sort()).toStrictEqual([
    'accountId',
    'activeStatus',
    'displayCardNumber',
    'key',
  ]);
}

/**
 * Asserts the identifier columns render on the fixed-pitch token rather than a literal face.
 *
 * Assumptions: the migration plan maps fixed-pitch money and identifier columns to the design system's
 * code-font token, and the point of the mapping is column alignment -- the terminal's face was
 * monospaced, so a proportional one would ragged the digits the operator scans down. The token is read
 * through `TYPOGRAPHY_TOKENS.fixedPitchData` so the name lives in the theme bridge.
 *
 * Assumptions: the resolved style must be a CSS VARIABLE REFERENCE and not a resolved value. The theme
 * is switched to a variables surface, so writing the resolved face into an inline style would freeze it
 * at render and the element would stop tracking a later theme change -- which is a hardcoded value in
 * every sense that matters, differing from a typed literal only in who typed it. Asserting the `var(`
 * form is what distinguishes the two.
 * @returns {Promise<void>} Resolves once both identifier columns have been inspected.
 */
async function rendersIdentifierColumnsOnTheFixedPitchToken(): Promise<void> {
  // WHY : Assumptions: the token NAME is asserted here so that a rename in the theme bridge surfaces as
  //       a failure in this file rather than as a silently unstyled column.
  expect(TYPOGRAPHY_TOKENS.fixedPitchData).toBe('fontFamilyCode');

  await renderBrowse(FULL_PAGE_ROWS);

  for (const value of [FIRST_ROW.displayCardNumber, FIRST_ROW.accountId]) {
    const rendered = screen.getByText(value);
    expect(rendered.style.fontFamily).toContain('var(');
  }
}

/**
 * Asserts identifiers survive rendering as strings, with their leading zeros intact.
 *
 * ⚠️ Trade-offs: a JSON number is parsed into an IEEE-754 double by most clients, which destroys
 * exactness, so every fixed-point and identifier value crosses this boundary as a STRING and is never
 * coerced with `Number`, a unary plus or a fixed-decimal formatter. This case is what makes the rule
 * observable on this screen: an eleven-character account identifier is `PIC X(11)` in
 * `app/cpy-bms/COCRDLI.CPY` L84 and carries leading zeros, and any numeric coercion anywhere in the
 * render path would drop them. So the zeros are the detector.
 *
 * Assumptions: the card browse carries no MONEY member at all -- `CardSummary` publishes four members
 * and none is an amount -- so the fixed-point half of the rule is satisfied here by there being nothing
 * to violate it with, and the identifier half is what remains to assert. Recording that keeps a reader
 * from concluding the money rule was overlooked on this screen.
 * @returns {Promise<void>} Resolves once each identifier has been found rendered verbatim.
 */
async function preservesIdentifiersAsStringsWithoutCoercion(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  for (const row of FULL_PAGE_ROWS) {
    expect(row.accountId.startsWith('0')).toBe(true);

    // WHY : Assumptions: an exact-text query is the assertion. A coerced identifier would render as a
    //       shortened number and would not be found under its declared eleven-character spelling.
    expect(screen.getByText(row.accountId)).toBeInTheDocument();
  }
}

/*
 * WHY : Assumptions: the account filter this screen sends is the CARDAIX access path, not a
 *       convenience. `app/cbl/COCRDLIC.cbl` L215-L217 names `CARDAIX ` as a file in its own right --
 *       the alternate index over cards by account, which CICS surfaced as a second file the program
 *       reads under -- so narrowing the browse by account was a distinct read path rather than a filter
 *       applied after one. In the target it is the non-unique secondary index `idx_cards_account_id`
 *       behind the by-account lookup in `ui/src/api/cards.ts`, which is why the narrowing travels as a
 *       request member and resets the cursor rather than being applied to a page already fetched. The
 *       request-member and reset behaviours are asserted by
 *       `ui/src/screens/cardList/browseNarrowing.test.tsx`, so they are recorded here and not repeated.
 */

/**
 * Reads every design-system text control the screen rendered.
 *
 * Assumptions: the class is queried rather than the accessible names being listed, because the point
 * of the case below is that no control of this kind is rendered by a DIFFERENT mechanism than the
 * others. A name list would only find the controls it already knew about.
 * @returns {readonly HTMLElement[]} Every element carrying the design system's text-control class.
 */
function renderedTextControls(): readonly HTMLElement[] {
  return [...document.querySelectorAll<HTMLElement>('.ant-input')];
}

/**
 * Reads one of the three entry actions by its visible label.
 *
 * ⚠️ Assumptions: the control is reached through its TEXT and then its enclosing button, not through a
 * name-bearing role query, and this is a measured decision rather than a stylistic one. Timed in this
 * suite, `screen.getByRole('button', { name })` cost 22.9 seconds per call for the two disabled
 * actions against 5 milliseconds for `getByText` -- and 22.9 seconds again with `hidden: true`, which
 * rules out the accessibility filter and leaves the accessible-name computation over a document
 * carrying the design system's injected stylesheets. Three such calls made one case take 47 of this
 * file's 57 seconds. The role is still asserted, once, by the emphasis case, so nothing is given up.
 * @param {string} label - The control's visible label.
 * @returns {HTMLElement} The button carrying that label.
 * @throws {Error} If no button carries it, which the enclosing-element narrowing raises.
 */
function entryAction(label: string): HTMLElement {
  const button = screen.getByText(label).closest('button');
  if (button === null) {
    throw new Error(`no button carries the label ${label}`);
  }
  return button;
}

/**
 * Asserts one control's inline size is ceilinged at its declared character width.
 *
 * Assumptions: the `ch` term is matched rather than the whole declaration, because the padding term is
 * a theme custom property whose serialised form belongs to the design system and not to this file.
 * What this file is entitled to assert is the character count, which is the copybook's.
 *
 * Assumptions: `max-inline-size` is asserted and not a pixel width, because jsdom computes no layout --
 * a pixel assertion here would assert nothing. The remedy is a ceiling expressed in `ch` plus the
 * system's own horizontal control padding, so the declaration's presence and its character count are
 * exactly what can be established.
 * @param {HTMLElement} control - The control to inspect.
 * @param {number} declaredWidth - The width the field's PICTURE clause declares.
 * @returns {void} Nothing; the expectations throw on a mismatch.
 */
function expectDeclaredWidthCeiling(control: HTMLElement, declaredWidth: number): void {
  const ceiling = control.style.maxInlineSize;
  expect(ceiling, 'the control must carry a declared-width ceiling').not.toBe('');
  expect(
    ceiling,
    `the ceiling must be measured in ${String(declaredWidth)} character columns`,
  ).toContain(`${String(declaredWidth)}ch`);
  // WHY : Assumptions: the full-width base is asserted beside the ceiling because the helper keeps
  //       both -- a ceiling alone would fix the field's size and stop it shrinking inside a phone
  //       viewport, which is the sideways overflow the card screens have already been measured for.
  expect(control.style.inlineSize).toBe('100%');
}

/**
 * Asserts the screen renders no in-field clear control, hidden or otherwise.
 *
 * Purpose
 * -------
 * This is the case for two findings that share one cause. A browser found two interactive controls on
 * this screen that could not be seen -- `button.ant-input-clear-icon-hidden`, `visibility: hidden`,
 * 12 by 12 pixels, `tabIndex 0`, no `aria-hidden`, with a full four-state style set -- so a keyboard
 * operator hit two tab stops with nothing to land on, each below the 24-pixel pointer floor the
 * exported `TARGET_SIZE_AA_MINIMUM` constant records from success criterion 2.5.8 -- named in prose
 * rather than as a link target, because it is a VALUE and `jsdoc/no-undefined-types` resolves a link
 * target against the type namespace alone. The same prop is also why two
 * adjacent entry controls in one form measured 40 and 28 pixels tall: it wraps the control in an
 * affix wrapper and moves the variant class that carries the border, the padding and the control
 * height off the `<input>` and onto that wrapper, so the screen rendered two different kinds of
 * element both matching one selector.
 *
 * ⚠️ Assumptions: the mapset is what settles whether the affordance belongs at all, and it does not.
 * `ACCTSID` and `CARDSID` are plain `UNPROT` entry fields with nothing beside them
 * (`app/bms/COCRDLI.bms` L89 to L93 and L101 to L105), the only affordances the screen paints are the
 * three on its legend field at L335 to L339, and the program reaches an arm for exactly ENTER, PF3,
 * PF7 and PF8 (`app/cbl/COCRDLIC.cbl` L439 to L562). So the fix is to drop the affordance rather than
 * to inflate its glyph, and asserting ABSENCE is what fixes that decision in place.
 *
 * Assumptions: the second expectation is that every text control is the same KIND of element, which
 * is the height defect stated at its cause. jsdom computes no layout, so two heights cannot be
 * measured here; what can be established is that one selector no longer matches two different boxes.
 * @returns {Promise<void>} Resolves once the absence has been established.
 */
async function rendersNoInFieldClearControl(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  expect(document.querySelectorAll('.ant-input-clear-icon')).toHaveLength(0);
  expect(document.querySelectorAll('.ant-input-affix-wrapper')).toHaveLength(0);

  const controls = renderedTextControls();
  expect(controls.length).toBeGreaterThan(0);
  for (const control of controls) {
    expect(
      control.classList.contains('ant-input-outlined'),
      'every text control must be the same kind of box, so one theme height governs them all',
    ).toBe(true);
  }
}

/**
 * Asserts all three entry controls are measured from the widths their copybooks declare.
 *
 * Purpose
 * -------
 * The regression guard for the one-character row selector rendering about 217 pixels wide -- against
 * about 97 for the identical selector on the user browse -- and for the two filter fields carrying no
 * measure of their own at all. `.ant-input` is full-width by default, so an eleven-digit account
 * number, a sixteen-digit card number and a one-character action code were all rendered the width of
 * whatever box contained them.
 *
 * Assumptions: the widths come from the screen's own exported constants, which are the same values its
 * `maxLength` props carry, so the entry bound and the rendered measure cannot state two widths.
 * @returns {Promise<void>} Resolves once all three ceilings have been asserted.
 */
async function measuresEveryEntryFromItsDeclaredWidth(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  expectDeclaredWidthCeiling(accountFilterControl(), CARD_LIST_ACCOUNT_FILTER_WIDTH);
  expectDeclaredWidthCeiling(
    screen.getByLabelText(CARD_LIST_LABELS.cardNumberFilter.replace(/\s+/gu, ' ').trim()),
    CARD_LIST_CARD_FILTER_WIDTH,
  );
  expectDeclaredWidthCeiling(rowActionEntry(0), ROW_ACTION_DECLARED_WIDTH);
}

/**
 * Asserts the three entry actions are separated from each other and from the field they act on.
 *
 * Purpose
 * -------
 * A browser measured 0-pixel gaps between `Filter`, `Open detail` and `Open update`, so three
 * independent actions read as one segmented control whose segments looked like states of a single
 * choice -- while the key legend on the same screen separated its own controls by about 9 pixels. The
 * cause was that all three sat inside the card number's compact group, and a compact group exists to
 * render its members as one joined control.
 *
 * ⚠️ Assumptions: the gap is asserted to be the LEGEND'S OWN, by comparing the two inline values rather
 * than by naming a number. Both are written from `SPACING_TOKENS.sectionGapCompact` -- the actions row
 * here and `ui/src/layout/PfKeyBar.tsx` L449 for the legend -- so an identical serialised custom-property
 * reference is what proves one token feeds both. A pixel assertion would be asserting nothing in jsdom,
 * and a literal would pass while the two rows drifted apart.
 * @returns {Promise<void>} Resolves once the separation has been established.
 */
async function separatesTheEntryActionsFromTheEntryField(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  const actions = [
    CARD_LIST_ENTRY_CONTROL_LABELS.filter,
    CARD_LIST_ENTRY_CONTROL_LABELS.openDetail,
    CARD_LIST_ENTRY_CONTROL_LABELS.openUpdate,
  ].map(entryAction);

  for (const action of actions) {
    expect(
      action.closest('.ant-space-compact'),
      'no entry action may sit inside a joined control group',
    ).toBeNull();
  }

  const [filterAction] = actions;
  if (filterAction === undefined) {
    throw new Error('the screen rendered no filter action');
  }
  const row = filterAction.parentElement;
  if (row === null) {
    throw new Error('the filter action has no containing row');
  }

  /*
   * WHY : Assumptions: the row's CHILDREN are asserted rather than each action's parent, because that
   *       establishes the stronger property -- the row holds exactly these three controls in the
   *       mapset-independent order the screen renders them, and nothing else has been folded in beside
   *       them. Asserting each parent separately would pass for a row that also contained the entry
   *       field, which is the arrangement this case exists to rule out.
   */
  expect([...row.children]).toEqual(actions);
  expect(row.style.gap, 'the actions row must carry a gap').not.toBe('');
  expect(row.style.gap, "the gap must be the legend's own spacing step").toBe(
    keyLegend().style.gap,
  );
}

/**
 * Asserts exactly one control carries the emphasised treatment, and that it is the screen's submit.
 *
 * Purpose
 * -------
 * A browser found no primary-solid control anywhere on this screen, so `Filter`, which performs the
 * browse, was pixel-indistinguishable from `F3=Exit`, which leaves it.
 *
 * ⚠️ Assumptions: the emphasised control is the screen's ENTER action, which is what the design-system
 * mapping fixes -- AAP section 0.3.2 maps Enter and PF5 to `type="primary"` and PF3, PF4 and PF12 to
 * `default`. The mapset paints no Enter on its legend (`app/bms/COCRDLI.bms` L339 names only F3, F7
 * and F8), and the program's `CCARD-AID-ENTER` arms are the ones that edit both filters and read the
 * browse (`app/cbl/COCRDLIC.cbl` L517 and L545), so this control is that verb's affordance.
 *
 * Assumptions: EXACTLY one is asserted rather than at least one. Two emphasised controls on one screen
 * is the same defect stated the other way round -- nothing stands out when everything does -- and the
 * delegated legend keys must stay neutral, which a count is what establishes.
 * @returns {Promise<void>} Resolves once the single emphasised control has been identified.
 */
async function emphasisesTheScreensOwnSubmitAndNothingElse(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  const emphasised = [...document.querySelectorAll('button.ant-btn-primary')];
  expect(emphasised).toHaveLength(1);
  expect(emphasised[0]).toBe(
    screen.getByRole('button', { name: CARD_LIST_ENTRY_CONTROL_LABELS.filter }),
  );
}

/**
 * Asserts every browse row is a pointer target and can be reached and activated from the keyboard.
 *
 * Purpose
 * -------
 * A browser measured `cursor: auto` on these rows both at rest and hovered, with no `:hover` rule of
 * their own and no focus or active treatment at all -- so nothing about a row said it could be acted
 * on, even though every row carries a selection field and two controls that act on it.
 *
 * ⚠️ Assumptions: the outcome of activating a row is the REFERENCE'S own -- the cursor lands in that
 * row's selection field. `1250-SETUP-ARRAY-ATTRIBS` repositions the cursor onto a row by moving `-1`
 * into that row's selection-field length (`app/cbl/COCRDLIC.cbl` L766 to L773 and the four blocks
 * after it), so this is the terminal's gesture for directing an operator to a row and not an invented
 * navigation.
 *
 * ⚠️ Assumptions: the activation key is the space key and the case asserts that pressing it submitted
 * NO turn. Enter is claimed document-wide by `usePfKeys` as this screen's submit, so a row that
 * activated on Enter would give one key two meanings; the read count is what proves the space key did
 * not reach that verb.
 * @returns {Promise<void>} Resolves once the affordance and the activation have been established.
 */
async function offersEveryRowAsAPointerAndKeyboardTarget(): Promise<void> {
  const { user } = await renderBrowse(FULL_PAGE_ROWS);
  const rows = browseBodyRows();
  expect(rows).toHaveLength(FULL_PAGE_ROWS.length);

  for (const row of rows) {
    expect(row.style.cursor, 'every row must offer a pointer affordance').toBe('pointer');
    expect(row.tabIndex, 'every row must be reachable from the keyboard').toBe(0);
  }

  const [firstRow, secondRow] = rows;
  if (firstRow === undefined || secondRow === undefined) {
    throw new Error('the rendered page has fewer than two rows');
  }
  const readsBefore = readCount();

  /**
   * Moves the operator's focus onto the second rendered row.
   *
   * Assumptions: a NAMED function rather than an inline arrow at the `act` call below, because
   * `ui/eslint.config.js` selects `jsdoc/require-jsdoc` with `publicOnly: false` -- which requires a
   * doc comment on a function in every position -- and Prettier moves a block comment attached to an
   * inline argument onto the preceding expression, detaching it from what it documents.
   *
   * ⚠️ Assumptions: it is bound to a `const` rather than declared with `function`, and the difference is
   * a type one. A hoisted declaration may be called before the guard above runs, so TypeScript discards
   * the narrowing that guard establishes and reports `secondRow` as possibly undefined inside it; a
   * closure created AFTER the guard keeps the narrowing, because the captured binding is `const`.
   * @returns {void} Nothing; the focus change is the effect.
   */
  const placeTheFocusOnTheSecondRow = (): void => {
    secondRow.focus();
  };

  await user.click(firstRow);
  expect(document.activeElement, 'a click on a row lands in that row').toBe(rowActionEntry(0));

  /*
   * WHY : Assumptions: the keyboard half is exercised on a DIFFERENT row from the pointer half, so a
   *       screen that answered only the pointer cannot pass it -- focus is already in row one's field
   *       when this begins, and the assertion is that it has moved to row two's.
   * WHY : Assumptions: the focus is applied inside `act` because it is a real state change. antd's own
   *       components update on focus, and React reports an update applied outside `act` as a warning
   *       rather than a failure -- so leaving it out would leave this file emitting one.
   */
  /*
   * WHY : ⚠️ Refactoring Rationale: the scope is entered WITHOUT `await`, where it was awaited. `act`
   *       returns a thenable only when its callback does, and this callback is synchronous -- so the
   *       `await` was awaiting a plain value, which `@typescript-eslint/await-thenable` reports and
   *       which also implies a flush this call does not perform. The synchronous form flushes the
   *       update before it returns, which is what the assertion below needs.
   */
  act(placeTheFocusOnTheSecondRow);
  expect(document.activeElement).toBe(secondRow);
  await user.keyboard('[Space]');

  expect(document.activeElement, 'activating a focused row lands in that row').toBe(
    rowActionEntry(1),
  );
  expect(readCount(), 'activating a row must not submit the turn').toBe(readsBefore);
}

/**
 * Registers the keyset-pagination cases.
 * @returns {void} Nothing.
 */
function keysetPaginationCases(): void {
  it('declares the row arity the mapset and the program both measure', declaresTheMapsetRowArity);
  it('holds its own horizontal overflow', holdsItsOwnHorizontalOverflow);
  it('disables the design system offset pager', disablesTheDesignSystemOffsetPager);
  it('renders exactly the rows the envelope delivered', rendersExactlyTheDeliveredRows);
  it('steps forward on the trailing cursor', stepsForwardOnTheTrailingCursor);
  it('steps backward on the leading cursor', stepsBackwardOnTheLeadingCursor);
  it('sends no page ordinal, offset or row total on any request', sendsNoOrdinalOnAnyRequest);
  it('refuses the backward step on the first page', refusesTheBackwardStepOnTheFirstPage);
  it(
    'reports the end of records and then the end of pages',
    reportsTheEndOfRecordsThenTheEndOfPages,
  );
  it('carries the narrowing into every paging step', carriesTheNarrowingIntoEveryPagingStep);
  it('withdraws no paging key at either boundary', withdrawsNoPagingKeyAtEitherBoundary);
  it('keeps the page size off the wire', keepsThePageSizeOffTheWire);
  it('adopts the narrowing an arrival carries', adoptsTheNarrowingAnArrivalCarries);
  it(
    'returns to the narrowed browse from the update screen',
    returnsToTheNarrowedBrowseFromTheUpdateScreen,
  );
}

/**
 * Registers the field-constraint and composition cases.
 * @returns {void} Nothing.
 */
function fieldConstraintCases(): void {
  it(
    'bounds both filter entries to their declared widths',
    boundsBothFilterEntriesToTheirDeclaredWidths,
  );
  it('bounds every row action entry to one character', boundsEveryRowActionEntryToOneCharacter);
  it('places the opening cursor on one control', placesTheOpeningCursorOnOneControl);
  it('renders no masked-entry control', rendersNoMaskedEntryControl);
  it(
    'composes the browse from design-system components',
    composesTheBrowseFromDesignSystemComponents,
  );
}

/**
 * Registers the message-fidelity cases.
 * @returns {void} Nothing.
 */
function messageFidelityCases(): void {
  it('anchors every sentence to its declaring line', anchorsEverySentenceToItsDeclaringLine);
  it(
    'preserves the declared spelling of every sentence',
    preservesTheDeclaredSpellingOfEverySentence,
  );
  it('paints the record action prompt', paintsTheRecordActionPrompt);
  it(
    'paints the search-condition sentence when nothing matches',
    paintsTheSearchConditionSentenceWhenNothingMatches,
  );
  it(
    'states the service sentence for a described refusal',
    statesTheServiceSentenceForADescribedRefusal,
  );
  it(
    'states the catalogued sentence when the refusal carries none',
    statesTheCataloguedSentenceWhenTheRefusalCarriesNone,
  );
  it(
    'states the outage sentence for a transient refusal',
    statesTheOutageSentenceForATransientRefusal,
  );
}

/**
 * Registers the attention-identifier cases.
 * @returns {void} Nothing.
 */
function attentionIdentifierCases(): void {
  it(
    'binds the four measured identifiers and no others',
    bindsTheFourMeasuredIdentifiersAndNoOthers,
  );
  it('announces the outstanding page turn through a live region', announcesTheOutstandingPageTurn);
  it(
    'declines only the key whose own turn is outstanding',
    declinesOnlyTheKeyWhoseTurnIsOutstanding,
  );
  it(
    'dispatches the forward step from keyboard and control',
    dispatchesTheForwardStepFromKeyboardAndControl,
  );
  it(
    'reports the backward refusal from keyboard and control',
    reportsTheBackwardRefusalFromKeyboardAndControl,
  );
  it('leaves the browse on the exit key', leavesTheBrowseOnTheExitKey);
}

/**
 * Registers the selection and navigation cases.
 * @returns {void} Nothing.
 */
function selectionCases(): void {
  it('refuses two selections without opening either', refusesTwoSelectionsWithoutOpeningEither);
  it('refuses an unrecognised action character', refusesAnUnrecognisedActionCharacter);
  it('selects the destination for each action code', selectsTheDestinationForEachActionCode);
}

/**
 * Asserts each entry label stays whole beside its field and is not joined to it.
 *
 * Purpose
 * -------
 * A browser measured the `Credit Card Number:` label at a 375-pixel width broken into stacked one- and
 * two-character pieces, beside an entry field 166 pixels wide. Both members of the pair were being
 * shrunk to fit one line that could not hold them, because the pair sat in a compact group -- a
 * construct whose whole purpose is to render its members as ONE joined control on ONE line.
 *
 * ⚠️ Assumptions: the mapset is what fixes "whole". `app/bms/COCRDLI.bms` L84-L88 and L96-L100 each
 * paint their label as a single 19-character field on a single row, so a label rendered as several
 * stacked fragments is not a narrower rendering of that field -- it is a different field.
 *
 * ⚠️ Assumptions: what is asserted is the DECLARATIONS that prevent the fragmenting, not a measured
 * width, because jsdom computes no layout and a pixel assertion here would assert nothing. The pair of
 * declarations is exactly what a browser needs to keep the label whole: refuse it a share of the
 * shrinking, and refuse it a line break. The row is required to wrap so that the width the label keeps
 * is taken from the line rather than from the entry.
 *
 * Assumptions: the gap is compared with the LEGEND'S, by value, for the same reason
 * `separatesTheEntryActionsFromEntryField` in this file does -- one token feeds both, so an identical
 * serialised custom-property reference is the proof, and a literal would pass while the rows drifted.
 * That case is named in prose rather than as a link target, because `jsdoc/no-undefined-types` resolves
 * a link target against the type namespace and a function is not in it.
 * @returns {Promise<void>} Resolves once both rows have been established.
 */
async function keepsEachEntryLabelWholeBesideItsField(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  const pairs: readonly (readonly [HTMLElement, string])[] = [
    [accountFilterControl(), CARD_LIST_LABELS.accountNumberFilter],
    [
      screen.getByLabelText(CARD_LIST_LABELS.cardNumberFilter.replace(/\s+/gu, ' ').trim()),
      CARD_LIST_LABELS.cardNumberFilter,
    ],
  ];

  for (const [field, labelText] of pairs) {
    expect(
      field.closest('.ant-space-compact'),
      'an entry field may not be joined to its own label',
    ).toBeNull();

    /*
     * WHY : Assumptions: the label is reached through the field's OWN `aria-labelledby` rather than by
     *       matching its text. That is the association a screen reader follows, so following it here
     *       asserts the naming and the layout on one element instead of trusting two lookups to land on
     *       the same node -- and it cannot be defeated by the whitespace the mapset pads these labels
     *       with, which `app/bms/COCRDLI.bms` L88 declares as four spaces inside a 19-character field.
     */
    const labelId = field.getAttribute('aria-labelledby');
    expect(labelId, `the entry for ${labelText} must name its own label`).not.toBeNull();
    const label = document.getElementById(labelId ?? '');
    if (label === null) {
      throw new Error(`the label element named by ${labelText} is not in the document`);
    }
    expect(label.textContent).toBe(labelText);
    expect(label.closest('.ant-space-compact')).toBeNull();
    expect(label.style.flexShrink, 'the label may not take a share of the shrinking').toBe('0');
    expect(label.style.whiteSpace, 'the label may not be broken across lines').toBe('nowrap');

    const row = label.parentElement;
    if (row === null) {
      throw new Error(`the label ${labelText} has no containing row`);
    }
    expect([...row.children]).toEqual([label, field]);
    expect(row, 'the label and its entry must be free to wrap').toHaveClass('ant-flex-wrap-wrap');
    expect(row.style.gap, 'the row must carry a gap').not.toBe('');
    expect(row.style.gap, "the gap must be the legend's own spacing step").toBe(
      keyLegend().style.gap,
    );
  }
}

/**
 * Registers the data-exposure cases.
 * @returns {void} Nothing.
 */
function controlAffordanceCases(): void {
  it('renders no in-field clear control', rendersNoInFieldClearControl);
  it('measures every entry from its declared width', measuresEveryEntryFromItsDeclaredWidth);
  it('keeps each entry label whole beside its field', keepsEachEntryLabelWholeBesideItsField);
  it('separates the entry actions from the entry field', separatesTheEntryActionsFromTheEntryField);
  it(
    "emphasises the screen's own submit and nothing else",
    emphasisesTheScreensOwnSubmitAndNothingElse,
  );
  it(
    'offers every row as a pointer and keyboard target',
    offersEveryRowAsAPointerAndKeyboardTarget,
  );
}

/**
 * Registers the data-exposure cases.
 * @returns {void} Nothing.
 */
function dataExposureCases(): void {
  it('masks every rendered card number', masksEveryRenderedCardNumber);
  it('exposes no member beyond the published four', exposesNoMemberBeyondThePublishedFour);
  it(
    'renders identifier columns on the fixed-pitch token',
    rendersIdentifierColumnsOnTheFixedPitchToken,
  );
  it(
    'preserves identifiers as strings without coercion',
    preservesIdentifiersAsStringsWithoutCoercion,
  );
}

describe('card list keyset pagination', keysetPaginationCases);
describe('card list field constraints', fieldConstraintCases);
describe('card list message fidelity', messageFidelityCases);
describe('card list attention identifiers', attentionIdentifierCases);
describe('card list selection and navigation', selectionCases);
describe('card list data exposure', dataExposureCases);
describe('card list control affordances', controlAffordanceCases);
