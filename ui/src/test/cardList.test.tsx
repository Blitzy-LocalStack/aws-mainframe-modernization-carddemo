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

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { getDefaultNormalizer, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { listCards } from '../api/cards';
import type { CardListQuery, CardSummary, PageDirection } from '../api/types';
import { MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, UNIFORM_PF_KEY_LABELS } from '../layout/PfKeyBar';
import { PROGRAM_MESSAGES, SHARED_MESSAGES, STATUS_MESSAGES } from '../messages/messages';
import { PROGRAM_MESSAGE_SOURCES, SHARED_MESSAGE_SOURCES } from '../messages/messages';
import { CARD_SELECTOR_LENGTH, cardDetailPath, cardEditPath } from '../routes/cards';
import {
  CARD_LIST_ACCOUNT_FILTER_WIDTH,
  CARD_LIST_CARD_FILTER_WIDTH,
  CARD_LIST_LABELS,
  CARD_LIST_PAGE_SIZE,
  CARD_LIST_ROW_ACTION_CODES,
  reduceCardListSelection,
} from '../screens/cardList/index';
import CardListScreen from '../screens/cardList/index';
import { TYPOGRAPHY_TOKENS } from '../theme/tokens';
import { LEADING_CURSOR, TRAILING_CURSOR, pageResponse, pressPfKey } from './setup';
import { expectMaxLength, expectVerbatimMessage, renderInAppShell } from './setup';

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
 * Asserts the record-action prompt is painted once a page has rows to act on.
 *
 * Assumptions: the prompt names both action codes, which is where the selection column's domain comes
 * from -- `app/cbl/COCRDLIC.cbl` L116 tells the operator to type `S` for detail or `U` to update. The
 * screen exports those two characters as `CARD_LIST_ROW_ACTION_CODES`, and both are asserted to appear
 * in the sentence so the control's domain and the sentence describing it cannot drift apart.
 * @returns {Promise<void>} Resolves once the prompt has been located.
 */
async function paintsTheRecordActionPrompt(): Promise<void> {
  await renderBrowse(FULL_PAGE_ROWS);

  await waitFor(
    /**
     * Holds until the prompt has been painted.
     * @returns {void} Nothing; the assertion is the wait's condition.
     */
    function promptPainted(): void {
      expectVerbatimMessage(CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.text);
    },
    { timeout: ASYNC_CONDITION_TIMEOUT_MS },
  );

  expect(CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.text).toContain(CARD_LIST_ROW_ACTION_CODES.detail);
  expect(CARD_LIST_STATUS.WS_INFORM_REC_ACTIONS.text).toContain(CARD_LIST_ROW_ACTION_CODES.update);
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
 * Registers the keyset-pagination cases.
 * @returns {void} Nothing.
 */
function keysetPaginationCases(): void {
  it('declares the row arity the mapset and the program both measure', declaresTheMapsetRowArity);
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
