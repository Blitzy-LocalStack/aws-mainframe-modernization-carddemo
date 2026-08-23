/**
 * @file Component suite for the card-detail screen, mounted at `/cards/:cardKey`.
 *
 * Purpose
 * -------
 * WHAT: this suite holds the migrated card-detail screen to the contracts its 3270 source declares --
 * the field widths of mapset `COCRDSL` / map `CCRDSLA`, the message text of program `COCRDSLC`, the
 * two attention identifiers that program admits, the masking posture the primary account number is
 * under, and the field-error treatment `app/cpy/CSSETATY.cpy` templates. The subject is the
 * `CardDetailScreen` component of `ui/src/screens/cardDetail/index.tsx`, mounted through the shared
 * harness in `./setup` so it reaches the same provider stack, router and application frame that
 * `ui/src/App.tsx` and `ui/src/router.tsx` build around it in production.
 *
 * WHAT: the module has no parameters, exports nothing and returns nothing -- the analogues Rule 1 asks
 * of a module entry point resolve here as follows. Its INPUTS are the fixtures below plus the four
 * modules it mocks or reads: the card client `../api/cards`, the message catalog
 * `ui/src/messages/messages.ts`, the route contract `ui/src/routes/cards.ts` and the design tokens in
 * `ui/src/theme/tokens.ts`. Its OUTPUT is the pass or fail verdict of each registered case. The
 * EXCEPTIONS it can raise are Testing Library's own query failures and the harness's
 * `assertPatternMatchesEntry` error, which reports a route pattern that cannot match the address a
 * case opens -- both are assertion failures rather than defects in the subject.
 *
 * Why this suite is the only verification this screen gets
 * -------------------------------------------------------
 * The COBOL parity oracle under `tests/` cannot reach this screen, and says so in as many words:
 * "Online `CO*` CICS programs cannot run end-to-end without a CICS runtime (absent on the runner);
 * only their extractable field-validation logic is unit-tested" (`tests/README.md` section 1.1). No
 * golden master exists for `COCRDSLC` -- the golden-master layer covers the batch chain -- so nothing
 * downstream compares this screen against the terminal it replaces. Every citation below is therefore
 * to the reference SOURCE rather than to a recorded output, and the assertions are the whole of the
 * parity evidence for this screen.
 *
 * Documentation obligation, and why it is double-anchored
 * ------------------------------------------------------
 * Two independent authorities impose the same obligation on this file and they agree, so this suite
 * extends an established house convention rather than introducing one. Rule 1 (Explainability), the
 * single user-specified rule on this project, requires a docstring stating purpose, parameters,
 * returns and exceptions on every function and module entry point, and requires each non-obvious
 * decision to carry an inline rationale under one of four named categories. `tests/README.md`
 * section 12 imposes the identical duty on "every new test, fixture builder, helper, mock, and runner
 * routine" and calls it "a hard review gate". The written form follows
 * `docs/CODE_DOCUMENTATION_STANDARD.md`.
 *
 * Trade-offs: the four rationale labels are written in their PLURAL form -- `Assumptions:`,
 * `Trade-offs:`, `Alternatives Considered:`, `Refactoring Rationale:`. The singular idiom reads more
 * consistently with the reference suite under `tests/`, and it is not available here: the executable
 * gate `config/rule1/rule1_gate.py` treats the singular spellings of the first two as prohibited
 * variants and fails the run on them -- they are not written out even in this explanation, for the
 * reason that gate gives for assembling them from fragments in its own source -- and
 * `docs/CODE_DOCUMENTATION_STANDARD.md` rules on this exact conflict at its
 * "four labels, and their one permitted written form" section. That gate runs in
 * `.github/workflows/services-ci.yml` and `.github/workflows/infra-ci.yml` and passes on this tree
 * today, so the plural form is what keeps it passing. The same gate permits a `WHAT:` comment only
 * inside a file's leading header block, which is why every rationale below this block is introduced
 * with `WHY :` instead.
 *
 * Relationship to the sibling suite beside the screen
 * ---------------------------------------------------
 * Alternatives Considered: folding these cases into `ui/src/screens/cardDetail/cardDetail.test.tsx`,
 * which already covers this screen's edit chain, its failure mapping and its rendering. Rejected
 * because that file assembles its own `ConfigProvider` and `MemoryRouter`, and `./setup` names it
 * specifically as the arrangement the shared harness exists to remove: a test that builds its own
 * provider stack asserts against a tree the application does not build, and the divergence is silent.
 * This suite goes through `renderInAppShell`, so the screen reaches its frame through the `<Outlet />`
 * the router fills, and the zones this screen DELEGATES to that frame -- the row-23 error line and the
 * function-key legend, both published in its `useShellSlot` call -- are observable here at all. The
 * two files therefore assert different things about one screen and neither restates the other.
 */

import { act, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { getAdminCardDetail, getCard, lookupCard } from '../api/cards';
import type { CardDetail } from '../api/types';
import { MASKED_CARD_NUMBER } from '../api/masking';
import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP } from '../hooks/useAuth';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, PRIMARY_ACTION_AIDS } from '../layout/PfKeyBar';
import {
  ACCESS_DENIED_NOT_AUTHORIZED,
  INVALID_KEY_PRESSED,
  SHARED_MESSAGES,
  SHARED_MESSAGE_SOURCES,
  STATUS_MESSAGES,
} from '../messages/messages';
import {
  CARD_DETAIL_ROUTE,
  CARD_SELECTOR_LENGTH,
  cardDetailPath,
  isCardNumber,
  isCardSelector,
} from '../routes/cards';
import { FIELD_ERROR_TOKENS } from '../theme/tokens';
import {
  apiError,
  expectMaxLength,
  expectVerbatimMessage,
  pressPfKey,
  renderInAppShell,
  renderWithProviders,
  seedSession,
} from './setup';
import type { HarnessRenderResult } from './setup';
import { CardDetailScreen } from '../screens/cardDetail/index';

/*
 * WHY : Assumptions: the runner APIs above are IMPORTED by name even though
 *       `ui/vitest.config.ts` sets `globals: true`, and the import is what makes this file compile
 *       rather than a stylistic preference. `ui/tsconfig.json` keeps `"types": []`, so no ambient
 *       declaration of `describe`, `it`, `expect` or `vi` is reachable from `ui/src` and a file that
 *       omitted the import would fail `tsc --noEmit` on the symbol it omitted. That pairing is
 *       deliberate and is argued at the `globals` option itself: the injection governs what exists
 *       inside the runner, the empty `types` list governs what the compiler believes, and only the
 *       latter can tell a test from a screen. Measured across `ui/src` at the time of writing, 81 of
 *       81 test files import these names.
 * WHY : Assumptions: the subject is the NAMED export `CardDetailScreen` and this screen publishes no
 *       default export. `ui/src/router.tsx` reaches it the same way, mapping the named export onto
 *       `default` inside its own `lazy` callback, so importing the name is importing what the
 *       application mounts.
 * WHY : ⚠️ Assumptions: two of the imports above reach a module that OWNS a contract rather than the
 *       module that consumes it, and both are deliberate. `../routes/cards` publishes the route pattern
 *       and the selector length that `ui/src/router.tsx` mounts this screen with and that the screen
 *       reads its parameter under, and `../api/masking` publishes the pattern that `../api/cards`
 *       validates every card rendering against; neither owner re-exports what it holds, so the owning
 *       module is the only place either value exists.
 *       Alternatives Considered: writing `'/cards/:cardKey'`, `59` and the mask expression as literals
 *       here. Rejected because each would become a second, unreviewed spelling of a contract this suite
 *       exists to check -- a hand-counted selector would fail on a contract change for a reason
 *       unrelated to the behaviour under test, and a hand-written route pattern is exactly the drift
 *       that makes a harness render a subject into an empty document.
 */

/*
 * WHY : Alternatives Considered: the card client is mocked with `vi.mock` given NO factory -- the
 *       automatic form -- and the spies are then reached through `vi.mocked`. The factory form is the
 *       other option and is what the sibling suite uses; it is declined here because a factory
 *       referencing spy constants is evaluated during the import phase, which forces the subject to be
 *       reached through a dynamic `await import` to stay clear of the temporal dead zone. The automatic
 *       form has no such ordering constraint, so the subject is a static import and `vi.mocked` keeps
 *       every spy typed from the real declaration rather than from a hand-written stand-in.
 * WHY : Assumptions: the CLIENT is mocked and not the transport, because these cases are about what
 *       the screen does with each outcome -- which sentence it paints, which address it enters, what
 *       it renders of the record -- and not about how a request is serialised. The client's own
 *       conformance to `card-api.yaml` is asserted where the client is defined, in
 *       `ui/src/api/cards.test.ts`. No network endpoint and no credential appears anywhere in this
 *       file, and no request-interception library is declared in `ui/package.json` at all, so there is
 *       none to install even if a case wanted one.
 */
vi.mock('../api/cards');

/** Screen-level messages this screen's own program contributes, keyed by its condition names. */
const MESSAGES = STATUS_MESSAGES.COCRDSLC;

/**
 * Widths the symbolic map declares for every field this screen carries.
 *
 * Assumptions: each figure is a citation rather than a choice, and each is transcribed from
 * `app/cpy-bms/COCRDSL.CPY` at the line named beside it. The two UNPROT fields are held to theirs by
 * `maxLength` on a rendered control; the display fields are held to theirs by the width of the value
 * the screen paints, which is the only surviving form of a constraint the terminal enforced in
 * hardware.
 *
 * Trade-offs: the header-band and message-field widths are recorded here and are asserted only where
 * this screen is the surface that paints them. The title band and the message band belong to
 * `ui/src/layout/AppShell.tsx` and `ui/src/layout/MessageBand.tsx`, whose own suites own their
 * contracts; recording the figures anyway keeps this table a complete census of the mapset, so a
 * reader can tell a width that is asserted elsewhere from one that is asserted nowhere.
 */
const DECLARED_WIDTHS = {
  /** `ACCTSIDI PIC X(11)` at `app/cpy-bms/COCRDSL.CPY` L60; `ACCTSID LENGTH=11` at `app/bms/COCRDSL.bms` L84-L88. */
  accountSearch: 11,
  /** `CARDSIDI PIC X(16)` at `app/cpy-bms/COCRDSL.CPY` L66; `CARDSID LENGTH=16` at `app/bms/COCRDSL.bms` L96-L100. */
  cardSearch: 16,
  /** `CRDNAMEI PIC X(50)` at `app/cpy-bms/COCRDSL.CPY` L72. */
  nameOnCard: 50,
  /** `CRDSTCDI PIC X(1)` at `app/cpy-bms/COCRDSL.CPY` L78. */
  activeStatus: 1,
  /** `EXPMONI PIC X(2)` at `app/cpy-bms/COCRDSL.CPY` L84. */
  expiryMonth: 2,
  /** `EXPYEARI PIC X(4)` at `app/cpy-bms/COCRDSL.CPY` L90. */
  expiryYear: 4,
  /** `TRNNAMEI PIC X(4)` at `app/cpy-bms/COCRDSL.CPY` L24. */
  transactionId: 4,
  /** `TITLE01I PIC X(40)` at `app/cpy-bms/COCRDSL.CPY` L30. */
  organisationTitle: 40,
  /** `CURDATEI PIC X(8)` at `app/cpy-bms/COCRDSL.CPY` L36. */
  currentDate: 8,
  /** `PGMNAMEI PIC X(8)` at `app/cpy-bms/COCRDSL.CPY` L42. */
  programName: 8,
  /** `TITLE02I PIC X(40)` at `app/cpy-bms/COCRDSL.CPY` L48. */
  applicationTitle: 40,
  /** `CURTIMEI PIC X(8)` at `app/cpy-bms/COCRDSL.CPY` L54. */
  currentTime: 8,
  /** `INFOMSGI PIC X(40)` at `app/cpy-bms/COCRDSL.CPY` L96 -- the row-20 informational line. */
  informationLine: 40,
  /** `ERRMSGI PIC X(80)` at `app/cpy-bms/COCRDSL.CPY` L102 -- the row-23 error line. */
  errorLineDisplay: 80,
  /** `FKEYSI PIC X(75)` at `app/cpy-bms/COCRDSL.CPY` L108 -- the dedicated row-24 legend field. */
  keyLegend: 75,
} as const;

/*
 * WHY : ⚠️ Assumptions: `errorLineDisplay` above is 80 and the message CONTENT contract is 75, and both
 *       numbers are correct at the same time. `app/cpy-bms/COCRDSL.CPY` L102 and L194 declare this
 *       mapset's `ERRMSGI`/`ERRMSGO` at `PIC X(80)`, which is one of only two such declarations in the
 *       application -- `app/cpy-bms/COCRDUP.CPY` is the other, and nineteen mapsets declare `X(78)`.
 *       The string that is MOVED into that field is narrower than the field: `app/cpy/CVCRD01Y.cpy`
 *       declares `CCARD-ERROR-MSG PIC X(75)` at L28 and `CCARD-RETURN-MSG PIC X(75)` at L29, and
 *       `app/cbl/COCRDSLC.cbl` L494 moves the second of those into `ERRMSGO`. So 80 is the width of
 *       the painted DISPLAY field and 75 is the width of the CONTENT it can hold, and
 *       `ui/src/layout/MessageBand.tsx` publishes the 75 as its own content constant. Both are
 *       recorded so that a later reader does not "correct" the 75 to 80, or the 80 to 78, having found
 *       only one of them.
 */

/**
 * Width of the message CONTENT this program's two message fields can hold.
 *
 * ⚠️ Assumptions: 75, from `CCARD-ERROR-MSG PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28 and
 * `CCARD-RETURN-MSG PIC X(75)` at L29 -- and NOT the 80 of `DECLARED_WIDTHS.errorLineDisplay`, which is
 * the width of the `ERRMSGI` display field that paints it. Both figures are recorded because this is
 * one of only two mapsets declaring an 80-wide error field, so a reader finding one number without the
 * other is liable to "correct" it to the other.
 */
const MESSAGE_CONTENT_WIDTH_FROM_CVCRD01Y = 75;

/**
 * A card selector of the exact shape the service mints and the route accepts.
 *
 * Assumptions: the length comes from `CARD_SELECTOR_LENGTH` rather than from a hand-counted literal.
 * `ui/src/routes/cards.ts` refuses any other length outright, so a counted string would fail this
 * suite on a contract change for a reason unrelated to the behaviour under test.
 */
const A_SELECTOR = 'A'.repeat(CARD_SELECTOR_LENGTH);

/** A card number at the sixteen-digit width `CARDSIDI` declares, used as typed search input. */
const A_CARD_NUMBER = '4111111111111111';

/**
 * An account number at the eleven-digit width `ACCTSIDI` declares.
 *
 * Assumptions: it carries LEADING ZEROS deliberately. `app/cpy/CVCRD01Y.cpy` declares the field as
 * `CC-ACCT-ID PIC X(11)` at L34 with a numeric redefinition at L36, so it is characters on the wire
 * and a number only inside arithmetic -- and a value routed through a JavaScript number would shed
 * these zeros and stop matching the declared width.
 */
const AN_ACCOUNT_NUMBER = '00000000123';

/**
 * The masked rendering an ordinary read is permitted to publish.
 *
 * Assumptions: twelve mask characters then the last four digits, which is the form
 * `ui/src/api/masking.ts` publishes as `MASKED_CARD_NUMBER` and the form the contract's own pattern
 * requires. It is sixteen characters wide, so it fits `CARDSIDI PIC X(16)` exactly as the whole number
 * would.
 */
const A_MASKED_RENDERING = '************1111';

/** An embossed name occupying the whole of `CRDNAMEI PIC X(50)`, so the width is exercised rather than assumed. */
const A_NAME_AT_THE_DECLARED_WIDTH = 'ALEKSANDRA WORTHINGTON-CHASE OF BOROUGH MARKET LTD';

/** The stored expiry as the account master holds it, `CARD-EXPIRAION-DATE PIC X(10)` at `app/cpy/CVACT02Y.cpy` L9. */
const A_STORED_EXPIRY = '2029-01-31';

/** The month and year the two declared expiry parts render, `MM/YYYY`, with the stored day dropped. */
const THE_RENDERED_EXPIRY = '01/2029';

/** The five value labels the mapset paints down its body, in the order it paints them. */
const RECORD_LABEL_COUNT = 5;

/** How many controls the mapset leaves unprotected: `ACCTSID` at L84 and `CARDSID` at L96 and no others. */
const UNPROTECTED_FIELD_COUNT = 2;

/**
 * Builds the record the mocked reads answer with.
 *
 * Assumptions: every member the published `CardDetail` shape declares is supplied, so no case depends
 * on an absent member and a narrowing that the real response shape would fail cannot pass here.
 *
 * Assumptions: `displayCardNumber` carries the MASKED rendering, because that is the only rendering an
 * ordinary read is permitted to return. The whole sixteen-digit number is published solely by
 * `getAdminCardDetail`, and no fixture in this file carries one -- see the posture cases below.
 * @param {Partial<CardDetail>} [overrides] - Members to replace; every member has a default.
 * @returns {CardDetail} A complete card record.
 */
function aCard(overrides: Partial<CardDetail> = {}): CardDetail {
  return {
    key: A_SELECTOR,
    displayCardNumber: A_MASKED_RENDERING,
    accountId: AN_ACCOUNT_NUMBER,
    activeStatus: 'Y',
    embossedName: A_NAME_AT_THE_DECLARED_WIDTH,
    expirationDate: A_STORED_EXPIRY,
    version: 1,
    ...overrides,
  };
}

/**
 * Mounts the screen at one card's own address, with the selector-addressed read already answered.
 *
 * Assumptions: the route PATTERN is imported as `CARD_DETAIL_ROUTE` and the concrete address is built
 * by `cardDetailPath`, neither being spelled here. The pattern is `/cards/:cardKey` and the parameter
 * carries an opaque sealed selector; writing either by hand would let this suite drift from
 * `ui/src/router.tsx`, and the harness would then render the subject into an empty document because its
 * own `assertPatternMatchesEntry` rejects a pattern the address cannot match.
 * @param {CardDetail} [record] - The record the read answers with; a complete default is used when none
 *   is given.
 * @returns {Promise<HarnessRenderResult>} The rendered tree and the operator that drives it.
 */
async function renderAtACardAddress(record: CardDetail = aCard()): Promise<HarnessRenderResult> {
  vi.mocked(getCard).mockResolvedValue(record);
  /*
   * WHY : Assumptions: the render is wrapped in `act` because the arranged read resolves DURING it,
   *       not after it. The harness resolves the design system, the theme, the router and the frame
   *       through dynamic imports, so several microtask turns elapse inside this call -- long enough
   *       for the mocked promise to settle and for the screen to leave its loading state. React
   *       reports that update as an unwrapped one unless the whole render is inside an act scope, and
   *       an `act` placed after the render is too late to cover it. The warning is noise rather than
   *       information, and noise loud enough to bury a real one.
   */
  return await act(
    /**
     * Renders the subject at one card's address and lets the arranged read settle.
     * @returns {Promise<HarnessRenderResult>} The rendered tree and its operator.
     */
    async (): Promise<HarnessRenderResult> =>
      await renderInAppShell(<CardDetailScreen />, {
        routePath: CARD_DETAIL_ROUTE,
        initialEntries: [cardDetailPath(A_SELECTOR)],
      }),
  );
}

/**
 * Mounts the screen at one card's address WITHOUT the application frame around it.
 *
 * ⚠️ Trade-offs: the frame is omitted for the cases that assert only the screen's own body, and the
 * saving is why. Every case here mounts a complete design-system screen into jsdom, and the module's
 * suite is measurably at the edge of its own budget on a four-core runner -- the configuration's
 * `testTimeout` was raised to 60000ms for exactly that reason, and one pre-existing file times out
 * under load with this file absent. Mounting the shell as well roughly doubles the tree for a case that
 * never queries it, so the cases that assert the delegated zones -- the row-23 error line and the
 * function-key legend, both published through `useShellSlot` -- take the framed arrangement below, and
 * every other case takes this one.
 *
 * Assumptions: the screen renders correctly with no frame mounted, and that is a property of the
 * delegation rather than a concession by this helper: `useShellSlot` publishes into a module-scoped
 * store rather than through a context, so an unmounted frame means the delegated zones have no
 * renderer, not that the screen has no provider.
 * @param {CardDetail} [record] - The record the read answers with; a complete default is used when none
 *   is given.
 * @returns {Promise<HarnessRenderResult>} The rendered tree and the operator that drives it.
 */
async function renderTheRecordUnframed(record: CardDetail = aCard()): Promise<HarnessRenderResult> {
  vi.mocked(getCard).mockResolvedValue(record);
  return await act(
    /**
     * Renders the subject at one card's address and lets the arranged read settle.
     * @returns {Promise<HarnessRenderResult>} The rendered tree and its operator.
     */
    async (): Promise<HarnessRenderResult> =>
      await renderWithProviders(<CardDetailScreen />, {
        routePath: CARD_DETAIL_ROUTE,
        initialEntries: [cardDetailPath(A_SELECTOR)],
      }),
  );
}

/**
 * Mounts the search arrival WITHOUT the application frame around it.
 *
 * Assumptions: the unframed arrangement is used for the same reason it is above, and the information
 * line remains observable because the SCREEN paints that band itself -- `INFOMSG` at `POS=(20,25)` sits
 * inside the screen's own field area (`app/bms/COCRDSL.bms` L139-L143), unlike the row-23 error line it
 * delegates.
 * @returns {Promise<HarnessRenderResult>} The rendered tree and the operator that drives it.
 */
async function renderTheSearchArrivalUnframed(): Promise<HarnessRenderResult> {
  const rendered = await act(
    /**
     * Renders the subject with no route parameter, which is the search-gathering arrival.
     * @returns {Promise<HarnessRenderResult>} The rendered tree and its operator.
     */
    async (): Promise<HarnessRenderResult> => await renderWithProviders(<CardDetailScreen />),
  );
  await screen.findByTestId(INFORMATION_BAND_TEST_ID);
  return rendered;
}

/**
 * Mounts the screen on the arrival that gathers search criteria, which issues no read.
 *
 * Assumptions: no route pattern is supplied, so the subject reads no parameter and reaches its
 * search-active state -- which is the arrival the reference program reaches under its own comment
 * "COMING FROM SOME OTHER CONTEXT / SELECTION CRITERIA TO BE GATHERED" at `app/cbl/COCRDSLC.cbl`
 * L350-L356, with both fields unprotected at L510-L511.
 * @returns {Promise<HarnessRenderResult>} The rendered tree and the operator that drives it.
 */
async function renderTheSearchArrival(): Promise<HarnessRenderResult> {
  /*
   * WHY : Assumptions: this arrival issues no read, and it is still wrapped in `act` for the same
   *       reason the addressed arrival is: the screen mounts with its loading flag set and clears it
   *       from an effect, and that update lands during the harness's own dynamic imports.
   */
  const rendered = await act(
    /**
     * Renders the subject with no route parameter, which is the search-gathering arrival.
     * @returns {Promise<HarnessRenderResult>} The rendered tree and its operator.
     */
    async (): Promise<HarnessRenderResult> => await renderInAppShell(<CardDetailScreen />),
  );
  await screen.findByTestId(INFORMATION_BAND_TEST_ID);
  return rendered;
}

/**
 * The two unprotected controls the mapset declares, in the order it paints them.
 *
 * Assumptions: the pair is located by ROLE and the count is asserted here, which makes the count part
 * of the contract rather than an implementation detail of this helper: `app/bms/COCRDSL.bms` marks
 * exactly two fields `UNPROT` -- `ACCTSID` at L84 and `CARDSID` at L96 -- so a third text box on this
 * screen would be a field the terminal did not offer, and every case that queries a control would
 * otherwise fail on an ambiguous match instead of naming the surplus.
 *
 * Alternatives Considered: querying each control by its label. Rejected because the labels are padded
 * `INITIAL=` literals with interior runs of spaces -- `'Account Number    :'` at `app/bms/COCRDSL.bms`
 * L83 -- and Testing Library's accessible-name matcher collapses whitespace, so a label query would
 * either need the collapsed spelling or a custom normaliser at every call site.
 * @returns {{ account: HTMLElement; card: HTMLElement }} The account and card search controls.
 * @throws {Error} If the screen paints a number of text boxes other than the two the mapset declares.
 */
function searchFields(): { account: HTMLElement; card: HTMLElement } {
  const controls = screen.getAllByRole('textbox');
  expect(controls).toHaveLength(UNPROTECTED_FIELD_COUNT);
  const [account, card] = controls;
  if (account === undefined || card === undefined) {
    throw new Error('the screen painted fewer unprotected controls than the mapset declares');
  }
  return { account, card };
}

/**
 * The legend controls the frame paints for the keys this screen delegated to it.
 *
 * Assumptions: the legend is located by the landmark role and accessible name `PfKeyBar` publishes,
 * which is how every other suite in this package reaches it, so a change to that region's identity
 * fails one shared query rather than each caller separately.
 * @returns {readonly HTMLElement[]} One control per rendered binding, in the order the bar paints them.
 */
function legendControls(): readonly HTMLElement[] {
  const bar = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  return within(bar).getAllByRole('button');
}

/**
 * Reads the accessible name of every legend control.
 * @returns {readonly string[]} One trimmed name per control.
 */
function legendControlNames(): readonly string[] {
  return legendControls().map(nameOfControl);
}

/**
 * Reads one control's accessible name.
 *
 * Assumptions: a named function rather than an inline arrow at the `map` call above, because
 * `ui/src/eslint.config.js` selects a function in every position with `publicOnly: false`, and Prettier
 * moves a block comment attached to an inline argument onto the preceding expression -- which detaches
 * it from what it documents.
 * @param {HTMLElement} control - One rendered control.
 * @returns {string} Its text content, trimmed of the whitespace the markup introduces.
 */
function nameOfControl(control: HTMLElement): string {
  return (control.textContent ?? '').trim();
}

/**
 * Locates one legend control by the browser key it advertises.
 *
 * Assumptions: the lookup is on `aria-keyshortcuts`, which `ui/src/layout/PfKeyBar.tsx` derives from
 * the same table `usePfKeys` resolves a real key press through. Selecting on the attribute therefore
 * asserts that the control and the keyboard binding name the same key, which is the property that makes
 * the click and the key press comparable at all.
 * @param {string} browserKey - The key the control advertises, for instance `Enter` or `F3`.
 * @returns {HTMLElement} The control advertising that key.
 * @throws {Error} If no legend control advertises it.
 */
function legendControlFor(browserKey: string): HTMLElement {
  const bar = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const control = within(bar)
    .getAllByRole('button')
    .find(
      /**
       * Reports whether one control advertises the key being sought.
       * @param {HTMLElement} candidate - One legend control.
       * @returns {boolean} `true` when its advertised shortcut is the key sought.
       */
      (candidate: HTMLElement): boolean =>
        candidate.getAttribute('aria-keyshortcuts') === browserKey,
    );
  if (control === undefined) {
    throw new Error(`no legend control advertises the key '${browserKey}'`);
  }
  return control;
}

/**
 * The label cells of the record view, which is one cell per field the mapset paints.
 *
 * Assumptions: the cells are counted through the design system's own description-list label class
 * rather than through table rows, because the screen passes a RESPONSIVE column count --
 * `RECORD_VIEW_COLUMNS` in `ui/src/layout/recordLayout.ts` is one column below the medium breakpoint
 * and two from it upward -- so the number of rows depends on the viewport while the number of labels
 * does not.
 * @returns {readonly Element[]} One element per rendered label cell.
 */
function recordLabelCells(): readonly Element[] {
  return Array.from(document.querySelectorAll('.ant-descriptions-item-label'));
}

/**
 * Fills the two search controls and leaves the cursor where the turn will put it.
 *
 * Assumptions: the fields are cleared before they are typed into, because a refused turn writes the
 * blank marker into the control itself (`app/cbl/COCRDSLC.cbl` L543 and L549 move a literal `'*'` into
 * the field's own output subfield), so a second turn in the same case would otherwise type after a
 * marker the first one left behind.
 *
 * ⚠️ Assumptions: the caller names where the operator leaves the cursor, and it is set to the control
 * the reference's own cursor rule will select for the turn about to be taken -- the `EVALUATE` at
 * `app/cbl/COCRDSLC.cbl` L515-L524, which sends the cursor to the first refused field and otherwise to
 * the account field. Two things follow, and the second is why the parameter exists rather than being
 * inferred here. It models a real operator: someone who fills the card number and then moves to the
 * account field they are about to leave empty is doing what the terminal's own tab order invited. And
 * it keeps the run's output clean, which was measured rather than assumed: when the cursor starts
 * somewhere else, the effect that places it moves focus off an input, the design system's input
 * wrapper dispatches state from its blur handler in `@rc-component/input`, and React reports every one
 * of those updates as unwrapped -- eighteen warnings from one refused turn. Naming the control at the
 * call site also puts the cursor rule in front of a reader of each case instead of leaving it implicit.
 *
 * Alternatives Considered: wrapping the key press in `act` to widen the scope over that effect flush.
 * Rejected after measurement: `@testing-library/react` disables the act environment for the duration of
 * any asynchronous user-event operation and restores it afterwards, so an outer `act` around a
 * user-event call trades the unwrapped-update warnings for "the current testing environment is not
 * configured to support act" warnings from user-event's own internal scope. Placing the cursor removes
 * the transfer instead of trying to contain its consequences.
 * @param {HarnessRenderResult} rendered - The rendered tree whose operator drives the entry.
 * @param {string} accountEntry - What to type into the account control; an empty string leaves it blank.
 * @param {string} cardEntry - What to type into the card control; an empty string leaves it blank.
 * @param {'account' | 'card'} cursorRestsOn - Which control the operator leaves the cursor in.
 * @returns {Promise<void>} Resolves once both controls hold the requested text and the cursor is placed.
 */
async function fillTheSearchFields(
  rendered: HarnessRenderResult,
  accountEntry: string,
  cardEntry: string,
  cursorRestsOn: 'account' | 'card',
): Promise<void> {
  const { account, card } = searchFields();
  await rendered.user.clear(account);
  await rendered.user.clear(card);
  if (accountEntry !== '') {
    await rendered.user.type(account, accountEntry);
  }
  if (cardEntry !== '') {
    await rendered.user.type(card, cardEntry);
  }
  await rendered.user.click(cursorRestsOn === 'account' ? account : card);
}

/**
 * Takes one turn by raising an attention identifier from a real key press.
 *
 * ⚠️ Assumptions: the press is NOT wrapped in `act`, and the omission is deliberate -- see the
 * measurement recorded on `fillTheSearchFields`. `@testing-library/user-event` already dispatches
 * through the wrapper `@testing-library/react` configures, and an outer scope of our own would sit
 * across the point where that wrapper disables the act environment, which produces a second class of
 * warning rather than removing the first.
 *
 * Assumptions: the turn is driven by a GENUINE keyboard event, because the 3270 original was
 * keyboard-only and so this is a keyboard contract. The helper exists so every case names the
 * attention identifier from `app/cpy/CSSTRPFY.cpy` rather than the browser key that raises it, and the
 * translation between the two stays in one shared place that derives it from the application's own
 * table.
 * @param {HarnessRenderResult} rendered - The rendered tree whose operator raises the identifier.
 * @param {'ENTER' | 'PFK03' | 'PFK05'} aid - The attention identifier to raise.
 * @returns {Promise<void>} Resolves once the press has been dispatched and settled.
 */
async function takeTheTurn(
  rendered: HarnessRenderResult,
  aid: 'ENTER' | 'PFK03' | 'PFK05',
): Promise<void> {
  await pressPfKey(rendered.user, aid);
}

/**
 * Takes one turn by activating the legend control for an attention identifier.
 *
 * Assumptions: the control is located by the key it advertises rather than by its label, so this
 * reaches the same binding the key press reaches -- which is the property the two drivers exist to
 * compare.
 * @param {HarnessRenderResult} rendered - The rendered tree whose operator clicks.
 * @param {string} browserKey - The key the control advertises, for instance `Enter` or `F3`.
 * @returns {Promise<void>} Resolves once the activation has been dispatched and settled.
 */
async function takeTheTurnFromTheLegend(
  rendered: HarnessRenderResult,
  browserKey: string,
): Promise<void> {
  await rendered.user.click(legendControlFor(browserKey));
}

/**
 * Reads the error treatment the design system applied to the item one control sits in.
 *
 * Assumptions: the state is read from the `Form.Item` ANCESTOR rather than from the control, because
 * `validateStatus` is an item-level prop and the design system expresses it as a class on the item's
 * wrapper. Reading it from the wrapper is what makes the assertion about the prop the screen passes
 * rather than about a colour, which `ui/src/theme/tokens.ts` owns and no test asserts as a literal.
 * @param {HTMLElement} control - One rendered control.
 * @returns {boolean} `true` when the item carrying the control is in its refused state.
 * @throws {Error} If the control is not inside a form item, which means the screen stopped wrapping it.
 */
function isFieldRefused(control: HTMLElement): boolean {
  const item = control.closest('.ant-form-item');
  if (item === null) {
    throw new Error(
      'the control is not inside a form item, so it can carry no field-level refusal',
    );
  }
  return item.classList.contains('ant-form-item-has-error');
}

/**
 * Both unprotected controls refuse more characters than their symbolic-map fields declare.
 *
 * Assumptions: the 3270 terminal enforced a field's width in hardware -- an eleventh keystroke into a
 * `PIC X(11)` field did nothing -- and `maxLength` is where that constraint survives. The two figures
 * are the mapset's own, not a preference.
 * @returns {Promise<void>} Resolves once both widths have been asserted.
 */
async function holdsBothSearchFieldsToTheirDeclaredWidths(): Promise<void> {
  await renderTheSearchArrivalUnframed();

  const { account, card } = searchFields();
  // WHY : Assumptions: eleven, from `ACCTSIDI PIC X(11)` at `app/cpy-bms/COCRDSL.CPY` L60 and
  //       `ACCTSID LENGTH=11` at `app/bms/COCRDSL.bms` L84-L88. The receiving field
  //       `CC-ACCT-ID PIC X(11)` at `app/cpy/CVCRD01Y.cpy` L34 is the same figure again, so a control
  //       held to it cannot carry a value the field edit would then refuse for width alone.
  expectMaxLength(account, DECLARED_WIDTHS.accountSearch);
  // WHY : Assumptions: sixteen, from `CARDSIDI PIC X(16)` at `app/cpy-bms/COCRDSL.CPY` L66 and
  //       `CARDSID LENGTH=16` at `app/bms/COCRDSL.bms` L96-L100, with `CC-CARD-NUM PIC X(16)` at
  //       `app/cpy/CVCRD01Y.cpy` L37 receiving it. The masked rendering this control shows on the
  //       other arrival is also sixteen characters, so neither arrival can overflow it.
  expectMaxLength(card, DECLARED_WIDTHS.cardSearch);
}

/**
 * The account control takes the cursor and it is the only control that does.
 *
 * Assumptions: the assertion is on FOCUS rather than on an `autofocus` attribute, because React
 * implements `autoFocus` by focusing the node on mount rather than by emitting the attribute, so focus
 * is the only observable form the single `IC` takes in a rendered tree.
 * @returns {Promise<void>} Resolves once the focused control has been identified.
 */
async function putsTheCursorOnTheOneFieldTheMapsetMarks(): Promise<void> {
  await renderTheSearchArrivalUnframed();

  const { account, card } = searchFields();
  // WHY : Assumptions: the account control, because `ACCTSID` carries this mapset's single `IC`
  //       attribute -- `ATTRB=(FSET,IC,NORM,UNPROT)` at `app/bms/COCRDSL.bms` L84, and no other
  //       `DFHMDF` of the 31 in that file carries one. The program's own cursor rule agrees: the
  //       `WHEN OTHER` arm of the `EVALUATE` at `app/cbl/COCRDSLC.cbl` L515-L524 returns the cursor
  //       here when nothing was refused.
  expect(account).toHaveFocus();
  expect(card).not.toHaveFocus();
}

/**
 * The retrieved record is painted through the design system's description list.
 *
 * Assumptions: `Descriptions` is the composition the target design records for this route, and the
 * reason is accessibility rather than taste: the mapset identifies each value by its absolute
 * `POS=(row,col)` on the retired character grid, and a description list emits each label and value as
 * one row a screen reader announces together.
 * @returns {Promise<void>} Resolves once the record view has been identified and counted.
 */
async function paintsTheRecordThroughTheDesignSystemsDescriptionList(): Promise<void> {
  await renderTheRecordUnframed();

  const record = await screen.findByText(A_MASKED_RENDERING);
  const list = record.closest('.ant-descriptions');
  expect(list).not.toBeNull();
  // WHY : Assumptions: the bordered variant is asserted through the class the design system emits for
  //       it, because a prop is not observable in a rendered tree by any other means. The variant is
  //       part of the target composition for a record view, and the border is what carries the cell
  //       separation the terminal got from the grid itself.
  expect(list?.classList.contains('ant-descriptions-bordered')).toBe(true);
  // WHY : Assumptions: five label cells, because the mapset paints five value fields down its body --
  //       `ACCTSID` (L84), `CARDSID` (L96), `CRDNAME` (L107), `CRDSTCD` (L116) and the `EXPMON`/
  //       `EXPYEAR` pair (L126 and L133) rendered as one expiry value. A sixth would be a field the
  //       terminal did not paint.
  expect(recordLabelCells()).toHaveLength(RECORD_LABEL_COUNT);
}

/**
 * The two declared expiry parts are painted, and the stored day is not.
 *
 * Assumptions: the mapset splits the expiry into `EXPMON LENGTH=2` and `EXPYEAR LENGTH=4` with a `'/'`
 * literal between them, and the stored value carries a day the terminal had no field for. Asserting the
 * two parts separately is what keeps the per-part width meaningful; a single combined value would let a
 * four-digit month or a two-digit year pass.
 * @returns {Promise<void>} Resolves once both parts and the dropped day have been asserted.
 */
async function paintsTheExpiryAsItsTwoDeclaredParts(): Promise<void> {
  await renderTheRecordUnframed();

  const expiry = await screen.findByText(THE_RENDERED_EXPIRY);
  const [month, year] = nameOfControl(expiry).split('/');
  // WHY : Assumptions: two characters, from `EXPMONI PIC X(2)` at `app/cpy-bms/COCRDSL.CPY` L84 and
  //       `EXPMON LENGTH=2` at `app/bms/COCRDSL.bms` L126-L129.
  expect(month).toHaveLength(DECLARED_WIDTHS.expiryMonth);
  // WHY : Assumptions: four characters, from `EXPYEARI PIC X(4)` at `app/cpy-bms/COCRDSL.CPY` L90 and
  //       `EXPYEAR LENGTH=4` at `app/bms/COCRDSL.bms` L133-L136.
  expect(year).toHaveLength(DECLARED_WIDTHS.expiryYear);
  // WHY : Assumptions: the stored form is absent, because `app/cbl/COCRDSLC.cbl` fills only the month
  //       at L480 and the year at L482 -- the map has no day field at all -- so painting the stored
  //       `PIC X(10)` value whole would show a component the terminal could not, and in the opposite
  //       order under a different separator.
  expect(screen.queryByText(A_STORED_EXPIRY)).toBeNull();
}

/**
 * The active flag is painted as the one-character value the record stores.
 *
 * Assumptions: both members of the domain are exercised, because a screen that expanded the flag into
 * words would satisfy a single-value assertion while contradicting the label beside it -- the mapset
 * names the domain in the label itself, `'Card Active Y/N   : '` at `app/bms/COCRDSL.bms` L115.
 * @returns {Promise<void>} Resolves once both domain values have been asserted at their declared width.
 */
async function paintsTheActiveFlagAtItsOneCharacterWidth(): Promise<void> {
  const rendered = await renderTheRecordUnframed(aCard({ activeStatus: 'Y' }));

  const active = await screen.findByText('Y');
  // WHY : Assumptions: one character, from `CRDSTCDI PIC X(1)` at `app/cpy-bms/COCRDSL.CPY` L78 and
  //       `CRDSTCD LENGTH=1` at `app/bms/COCRDSL.bms` L116-L119.
  expect(nameOfControl(active)).toHaveLength(DECLARED_WIDTHS.activeStatus);

  rendered.unmount();
  vi.mocked(getCard).mockResolvedValue(aCard({ activeStatus: 'N' }));
  await renderTheRecordUnframed(aCard({ activeStatus: 'N' }));

  const inactive = await screen.findByText('N');
  expect(nameOfControl(inactive)).toHaveLength(DECLARED_WIDTHS.activeStatus);
}

/**
 * The embossed name is painted whole at the width its field declares.
 * @returns {Promise<void>} Resolves once the name has been found at its declared width.
 */
async function paintsTheNameOnCardAtItsDeclaredWidth(): Promise<void> {
  await renderTheRecordUnframed();

  const name = await screen.findByText(A_NAME_AT_THE_DECLARED_WIDTH);
  // WHY : Assumptions: fifty characters, from `CRDNAMEI PIC X(50)` at `app/cpy-bms/COCRDSL.CPY` L72
  //       and `CRDNAME LENGTH=50` at `app/bms/COCRDSL.bms` L107-L109. The fixture occupies the field
  //       exactly, so a screen truncating at any narrower width fails here rather than passing on a
  //       short value.
  expect(nameOfControl(name)).toHaveLength(DECLARED_WIDTHS.nameOnCard);
}

/**
 * Identifiers survive the round trip as text, keeping the leading zeros a number would drop.
 *
 * Trade-offs: this asserts a STRING identity and never converts the rendered value, and the conversion
 * is what is being guarded against rather than merely avoided. A sixteen-digit card number reaches
 * 10^16, above the largest exactly representable integer at 2^53 - 1, so an IEEE-754 double silently
 * rounds its low-order digits; an eleven-digit account identifier additionally sheds its leading zeros
 * and stops matching the width the contract requires. Both losses are silent, which is why no
 * numeric coercion appears anywhere in this file.
 * @returns {Promise<void>} Resolves once both identifiers have been asserted as text.
 */
async function keepsEveryIdentifierAsTextRatherThanANumber(): Promise<void> {
  await renderTheRecordUnframed();

  const account = await screen.findByText(AN_ACCOUNT_NUMBER);
  // WHY : Assumptions: the rendered text is byte-identical to the stored value, leading zeros
  //       included, because `app/cpy/CVCRD01Y.cpy` declares the identifier as `PIC X(11)` at L34 and
  //       redefines it numerically at L36 -- characters on the wire, a number only inside arithmetic.
  expect(nameOfControl(account)).toBe(AN_ACCOUNT_NUMBER);
  expect(nameOfControl(account)).toHaveLength(DECLARED_WIDTHS.accountSearch);

  const rendering = await screen.findByText(A_MASKED_RENDERING);
  expect(nameOfControl(rendering)).toHaveLength(DECLARED_WIDTHS.cardSearch);
}

/**
 * The frame paints the transaction and program identifiers this screen delegates to it.
 *
 * Trade-offs: only the two identifiers are asserted, not the whole title band. The band's own contract
 * -- its two 40-character titles, its date and its clock -- belongs to `ui/src/layout/AppShell.tsx` and
 * its suite; what belongs to THIS screen is which identity it publishes, and these two values are that
 * identity.
 * @returns {Promise<void>} Resolves once both identifiers have been found at their declared widths.
 */
async function publishesItsOwnTransactionAndProgramIdentity(): Promise<void> {
  await renderAtACardAddress();

  // WHY : Assumptions: `CCDL` is this screen's transaction, as `app/csd/CARDDEMO.CSD` L347-L348
  //       defines it, and it is four characters because `TRNNAMEI PIC X(4)` at
  //       `app/cpy-bms/COCRDSL.CPY` L24 is the field that paints it.
  const transaction = await screen.findByText('CCDL');
  expect(nameOfControl(transaction)).toHaveLength(DECLARED_WIDTHS.transactionId);
  // WHY : Assumptions: `COCRDSLC` is the source program and it is eight characters because
  //       `PGMNAMEI PIC X(8)` at `app/cpy-bms/COCRDSL.CPY` L42 is the field that paints it. The
  //       migrated screen keeps painting the program name so an operator moving between the two
  //       systems can still name the screen they are on.
  const program = await screen.findByText('COCRDSLC');
  expect(nameOfControl(program)).toHaveLength(DECLARED_WIDTHS.programName);
}

/**
 * Registers the field-constraint and composition cases.
 * @returns {void} Nothing; registration is the effect.
 */
function fieldConstraintCases(): void {
  it(
    'holds both search fields to their declared widths',
    holdsBothSearchFieldsToTheirDeclaredWidths,
  );
  it('puts the cursor on the one field the mapset marks', putsTheCursorOnTheOneFieldTheMapsetMarks);
  it(
    'paints the record through the design system description list',
    paintsTheRecordThroughTheDesignSystemsDescriptionList,
  );
  it('paints the expiry as its two declared parts', paintsTheExpiryAsItsTwoDeclaredParts);
  it(
    'paints the active flag at its one-character width',
    paintsTheActiveFlagAtItsOneCharacterWidth,
  );
  it('paints the name on card at its declared width', paintsTheNameOnCardAtItsDeclaredWidth);
  it(
    'keeps every identifier as text rather than a number',
    keepsEveryIdentifierAsTextRatherThanANumber,
  );
  it(
    'publishes its own transaction and program identity',
    publishesItsOwnTransactionAndProgramIdentity,
  );
}

describe('card detail field constraints', fieldConstraintCases);

/**
 * Where each of this program's thirteen catalogued sentences is declared.
 *
 * ⚠️ Alternatives Considered: the expected TEXT is deliberately absent from this table and only the
 * source line is written down. Retyping each sentence here was rejected for the reason the catalog
 * exists at all: a paraphrase in the screen and the same paraphrase in the test agree with each other,
 * so the case passes while fidelity is lost. What the table adds instead is the CITATION -- every
 * catalog entry carries a `line` member, so comparing it against the line measured in
 * `app/cbl/COCRDSLC.cbl` makes the provenance machine-checked rather than a claim in a comment. The
 * sentences themselves are asserted by rendering them and by the structural cases below, always
 * through the catalog value.
 *
 * Assumptions: the thirteen entries are the whole of `WS-INFO-MSG` and `WS-RETURN-MSG` as
 * `app/cbl/COCRDSLC.cbl` declares them at L126-L158. The fourteenth string this program can emit is
 * `'UNEXPECTED DATA SCENARIO'` at L377, which is shared with four other programs and so lives in the
 * catalog's shared group; it has its own case below.
 */
const CATALOGUED_SENTENCE_LINES = [
  { key: 'FOUND_CARDS_FOR_ACCOUNT', line: 130, field: 'WS-INFO-MSG' },
  { key: 'WS_PROMPT_FOR_INPUT', line: 132, field: 'WS-INFO-MSG' },
  { key: 'WS_EXIT_MESSAGE', line: 137, field: 'WS-RETURN-MSG' },
  { key: 'WS_PROMPT_FOR_ACCT', line: 139, field: 'WS-RETURN-MSG' },
  { key: 'WS_PROMPT_FOR_CARD', line: 141, field: 'WS-RETURN-MSG' },
  { key: 'NO_SEARCH_CRITERIA_RECEIVED', line: 143, field: 'WS-RETURN-MSG' },
  { key: 'SEARCHED_ACCT_ZEROES', line: 145, field: 'WS-RETURN-MSG' },
  { key: 'SEARCHED_ACCT_NOT_NUMERIC', line: 147, field: 'WS-RETURN-MSG' },
  { key: 'SEARCHED_CARD_NOT_NUMERIC', line: 149, field: 'WS-RETURN-MSG' },
  { key: 'DID_NOT_FIND_ACCT_IN_CARDXREF', line: 152, field: 'WS-RETURN-MSG' },
  { key: 'DID_NOT_FIND_ACCTCARD_COMBO', line: 154, field: 'WS-RETURN-MSG' },
  { key: 'XREF_READ_ERROR', line: 156, field: 'WS-RETURN-MSG' },
  { key: 'CODING_TO_BE_DONE', line: 158, field: 'WS-RETURN-MSG' },
] as const satisfies readonly {
  readonly key: keyof typeof MESSAGES;
  readonly line: number;
  readonly field: 'WS-INFO-MSG' | 'WS-RETURN-MSG';
}[];

/**
 * The composed diagnostic prefix this program builds and the migration withholds.
 *
 * ⚠️ Assumptions: this literal is written out precisely because it is asserted ABSENT, so it is the
 * thing being excluded rather than a second spelling competing with the catalog -- the prohibition on
 * retyped message text guards against a test that agrees with a paraphrase, and there is nothing here
 * to agree with. `app/cbl/COCRDSLC.cbl` L102-L121 composes `WS-FILE-ERROR-MESSAGE` from this prefix at
 * L104 plus an operation name, an internal file name and the CICS response and reason codes, and no
 * entry for it exists anywhere in `ui/src/messages/messages.ts`: a browser is the wrong place for a
 * dataset name and a `RESP` pair, and the correlation identifier on the problem document is how that
 * detail is recovered server-side instead.
 */
const THE_WITHHELD_DIAGNOSTIC_PREFIX = 'File Error: ';

/**
 * Every catalogued sentence carries the source line the program declares it at.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function citesTheSourceLineOfEveryCatalogueEntry(): void {
  // WHY : Assumptions: thirteen and not twelve or fourteen, because `app/cbl/COCRDSLC.cbl` declares
  //       exactly thirteen 88-level sentences across its two message fields at L126-L158. Asserting
  //       the count is what makes this table a census: an entry added to the catalog without a line
  //       measured for it fails here rather than going uncited.
  expect(Object.keys(MESSAGES)).toHaveLength(CATALOGUED_SENTENCE_LINES.length);

  for (const expected of CATALOGUED_SENTENCE_LINES) {
    const entry = MESSAGES[expected.key];
    expect(entry.line).toBe(expected.line);
    expect(entry.field).toBe(expected.field);
    // WHY : Assumptions: 75 for a `WS-RETURN-MSG` sentence and 40 for a `WS-INFO-MSG` one, from
    //       `CCARD-RETURN-MSG PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L29 and `WS-INFO-MSG PIC X(40)`
    //       at `app/cbl/COCRDSLC.cbl` L126. The 75 is the CONTENT width and is NOT the 80 of the
    //       `ERRMSGI` display field that paints it -- see the note beside `DECLARED_WIDTHS`.
    expect(entry.declaredWidth).toBe(
      expected.field === 'WS-INFO-MSG'
        ? DECLARED_WIDTHS.informationLine
        : MESSAGE_CONTENT_WIDTH_FROM_CVCRD01Y,
    );
    expect(entry.text.length).toBeLessThanOrEqual(entry.declaredWidth);
  }
}

/**
 * The exit sentence keeps the trailing padding its declaration carries.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function preservesTheTrailingPaddingOfTheExitSentence(): void {
  const entry = MESSAGES.WS_EXIT_MESSAGE;
  // WHY : ⚠️ Assumptions: the padding is CONTENT and not formatting. `app/cbl/COCRDSLC.cbl` L136-L137
  //       declares the 88-level value with fourteen trailing spaces inside the quotes, which makes the
  //       literal 34 characters against a 75-character field -- so the padding is neither the field's
  //       own fill nor an accident of the source's column layout. Transformation rule T8 carries every
  //       user-visible string across character-for-character, so trimming it here would be the one
  //       edit that makes the catalog agree with a value the program does not hold.
  expect(entry.text).not.toBe(entry.text.trimEnd());
  expect(entry.text.length).toBeGreaterThan(entry.text.trimEnd().length);
}

/**
 * The provisional sentence keeps all four of its dots.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function preservesTheFourDotsOfTheProvisionalSentence(): void {
  // WHY : Assumptions: four dots, not three. `app/cbl/COCRDSLC.cbl` L158 declares
  //       `'Looks Good.... so far'`, and an ellipsis normalised to three would be a different string
  //       from the one the program holds -- the kind of silent tidy-up rule T8 exists to forbid.
  expect(MESSAGES.CODING_TO_BE_DONE.text).toContain('....');
  expect(MESSAGES.CODING_TO_BE_DONE.text).not.toContain('.....');
}

/**
 * The two identically-worded account sentences are kept as two entries.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function keepsBothAccountSentencesDespiteTheirIdenticalWording(): void {
  // WHY : ⚠️ Assumptions: the duplication is the SOURCE's and is deliberate here.
  //       `app/cbl/COCRDSLC.cbl` declares `SEARCHED-ACCT-ZEROES` at L144-L145 and
  //       `SEARCHED-ACCT-NOT-NUMERIC` at L146-L147 with the same text, one for an all-zeroes entry and
  //       one for a non-numeric entry. Collapsing them into one entry would lose the record that two
  //       distinct conditions produce this sentence, which is exactly what a reader comparing the two
  //       systems needs; keeping two entries with two lines preserves it.
  expect(MESSAGES.SEARCHED_ACCT_ZEROES.text).toBe(MESSAGES.SEARCHED_ACCT_NOT_NUMERIC.text);
  expect(MESSAGES.SEARCHED_ACCT_ZEROES.line).not.toBe(MESSAGES.SEARCHED_ACCT_NOT_NUMERIC.line);
}

/**
 * The one uppercase sentence this program emits stays uppercase, against a mixed-case neighbourhood.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function keepsTheUppercaseAbendSentenceUppercase(): void {
  const sentence = SHARED_MESSAGES.UNEXPECTED_DATA_SCENARIO;
  // WHY : ⚠️ Assumptions: this program is mixed case throughout except here.
  //       `app/cbl/COCRDSLC.cbl` L377 moves `'UNEXPECTED DATA SCENARIO'` in its `WHEN OTHER` arm while
  //       every one of its thirteen own sentences is mixed case -- and its sibling browse program
  //       `COCRDLIC` is uppercase throughout. Rule T8 requires each string carried across as its own
  //       program declares it, so the casing is neither normalised up nor down.
  expect(sentence).toBe(sentence.toUpperCase());
  const ownSentences = CATALOGUED_SENTENCE_LINES.map(textOfCatalogueEntry);
  expect(ownSentences.every(isEntirelyUppercase)).toBe(false);
  // WHY : Assumptions: the provenance is asserted through the catalog's own shared-source register,
  //       which records this program among the five that emit the sentence and cites L377 for it. The
  //       sentence sits in the shared group rather than under this program because five programs move
  //       it, so a per-program key would have named one of them arbitrarily.
  const citations = SHARED_MESSAGE_SOURCES.UNEXPECTED_DATA_SCENARIO;
  const here = citations.find(isThisProgramsCitation);
  expect(here?.lines).toStrictEqual([377]);
}

/**
 * Reads one catalogued sentence from its table entry.
 * @param {object} entry - One row of the citation table.
 * @param {keyof typeof MESSAGES} entry.key - The catalog key that row cites.
 * @returns {string} The sentence the catalog holds for that key.
 */
function textOfCatalogueEntry(entry: { readonly key: keyof typeof MESSAGES }): string {
  return MESSAGES[entry.key].text;
}

/**
 * Reports whether a sentence is written entirely in upper case.
 * @param {string} sentence - One catalogued sentence.
 * @returns {boolean} `true` when the sentence equals its own upper-cased form.
 */
function isEntirelyUppercase(sentence: string): boolean {
  return sentence === sentence.toUpperCase();
}

/**
 * Reports whether a shared-source citation names this screen's own program.
 * @param {object} citation - One entry of a shared sentence's source register.
 * @param {string} citation.file - The repository path that entry cites.
 * @returns {boolean} `true` when the citation names `app/cbl/COCRDSLC.cbl`.
 */
function isThisProgramsCitation(citation: { readonly file: string }): boolean {
  return citation.file === 'app/cbl/COCRDSLC.cbl';
}

/**
 * The search arrival paints the program's own prompt for input.
 * @returns {Promise<void>} Resolves once the sentence has been found.
 */
async function paintsThePromptForInputOnTheSearchArrival(): Promise<void> {
  await renderTheSearchArrivalUnframed();

  // WHY : Assumptions: this sentence rather than a blank line, because the program applies it as a
  //       FALLBACK whenever its information field would otherwise be empty -- `IF WS-NO-INFO-MESSAGE
  //       SET WS-PROMPT-FOR-INPUT` at `app/cbl/COCRDSLC.cbl` L492-L493, immediately before it moves
  //       the field into `INFOMSGO` at L496. So the row-20 line is never empty on a sent map.
  const band = await screen.findByTestId(INFORMATION_BAND_TEST_ID);
  expect(band).toHaveTextContent(MESSAGES.WS_PROMPT_FOR_INPUT.text);
  expectVerbatimMessage(MESSAGES.WS_PROMPT_FOR_INPUT.text);
}

/**
 * A retrieved record confirms itself on the information line.
 * @returns {Promise<void>} Resolves once the confirmation has been found.
 */
async function confirmsARetrievedRecordOnTheInformationLine(): Promise<void> {
  await renderTheRecordUnframed();

  // WHY : Assumptions: the confirmation belongs to the row-20 `INFOMSG` field and not to the row-23
  //       error line, because the program drives the two from two different fields -- `WS-INFO-MSG`
  //       into `INFOMSGO` and `WS-RETURN-MSG` into `ERRMSGO` at `app/cbl/COCRDSLC.cbl` L494 and L496
  //       -- and it sets this one on a normal file response at L754 and L795.
  const band = await screen.findByTestId(INFORMATION_BAND_TEST_ID);
  /*
   * WHY : ⚠️ Assumptions: the RENDERED content is compared against the sentence with its boundary
   *       whitespace removed, while the catalog is asserted to keep that whitespace intact. The two
   *       halves are both necessary because the sentence carries LEADING spaces --
   *       `app/cbl/COCRDSLC.cbl` L129-L130 declares the value indented inside its own `PIC X(40)`
   *       field -- and no browser renders leading whitespace in flow content, so a rendered-text
   *       comparison including them can only ever fail. Asserting the padding where it survives, on the
   *       catalog value, keeps rule T8's character-for-character guarantee checked without asserting
   *       that the DOM does something the DOM does not do.
   */
  expect(MESSAGES.FOUND_CARDS_FOR_ACCOUNT.text).not.toBe(
    MESSAGES.FOUND_CARDS_FOR_ACCOUNT.text.trimStart(),
  );
  expect(band).toHaveTextContent(MESSAGES.FOUND_CARDS_FOR_ACCOUNT.text.trim());
}

/**
 * An empty turn reports the cross-field sentence and never the field prompt it overrides.
 * @returns {Promise<void>} Resolves once the reported sentence has been identified.
 */
async function reportsNoInputReceivedWhenNeitherFieldWasSupplied(): Promise<void> {
  const rendered = await renderTheSearchArrival();

  await takeTheTurn(rendered, 'ENTER');

  // WHY : ⚠️ Assumptions: `No input received` and NOT `Account number not provided`, and the
  //       distinction is the one exception to first-message-wins in the whole paragraph. The account
  //       edit claims the field first at `app/cbl/COCRDSLC.cbl` L654-L657 and the card edit's own
  //       prompt at L692-L697 is guarded by `IF WS-RETURN-MSG-OFF`, but the cross-field edit at
  //       L637-L639 then replaces the whole thing UNCONDITIONALLY -- so an operator pressing Enter on
  //       an empty screen can never see either field prompt.
  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text);
  expect(band).not.toHaveTextContent(MESSAGES.WS_PROMPT_FOR_ACCT.text);
}

/**
 * A turn missing one field reports that field's own prompt.
 * @returns {Promise<void>} Resolves once both single-field refusals have been reported.
 */
async function reportsTheMissingFieldsOwnPrompt(): Promise<void> {
  const rendered = await renderTheSearchArrival();

  await fillTheSearchFields(rendered, '', A_CARD_NUMBER, 'account');
  await takeTheTurn(rendered, 'ENTER');

  // WHY : Assumptions: the account prompt, from the blank arm of `2210-EDIT-ACCOUNT` at
  //       `app/cbl/COCRDSLC.cbl` L651-L658. Only the account field is blank on this turn, so the
  //       cross-field override at L637-L639 does not apply and the field's own sentence survives.
  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  expect(band).toHaveTextContent(MESSAGES.WS_PROMPT_FOR_ACCT.text);

  await fillTheSearchFields(rendered, AN_ACCOUNT_NUMBER, '', 'card');
  await takeTheTurn(rendered, 'ENTER');

  // WHY : Assumptions: the card prompt, from the blank arm of `2220-EDIT-CARD` at
  //       `app/cbl/COCRDSLC.cbl` L689-L697. Both fields are still required even though only the card
  //       number is used as the read key -- the account move in `9100-GETCARD-BYACCTCARD` is commented
  //       out at L740-L741 -- so an account-only search is impossible in the reference and here.
  await waitForBandToReport(MESSAGES.WS_PROMPT_FOR_CARD.text);
}

/**
 * Waits until the delegated error line reports one sentence.
 *
 * Assumptions: a named waiter rather than an inline callback, for the reason recorded at
 * `nameOfControl`: the lint configuration selects a function in every position, and Prettier detaches a
 * block comment attached to an inline argument.
 * @param {string} sentence - The catalogued sentence the band is expected to report.
 * @returns {Promise<void>} Resolves once the band reports it.
 */
async function waitForBandToReport(sentence: string): Promise<void> {
  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await waitFor(
    /**
     * Asserts the band's current content against the expected sentence.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(band).toHaveTextContent(sentence);
    },
  );
}

/**
 * A refused read reports the catalogued sentence its status selects.
 * @returns {Promise<void>} Resolves once each status has been mapped to its sentence.
 */
async function reportsTheCatalogueSentenceEachStatusSelects(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(apiError({ status: 404 }));
  const notFound = await act(
    /**
     * Renders the subject at a card address whose read is refused as absent.
     * @returns {Promise<HarnessRenderResult>} The rendered tree and its operator.
     */
    async (): Promise<HarnessRenderResult> =>
      await renderInAppShell(<CardDetailScreen />, {
        routePath: CARD_DETAIL_ROUTE,
        initialEntries: [cardDetailPath(A_SELECTOR)],
      }),
  );

  // WHY : Assumptions: 404 takes this program's `DID-NOT-FIND-ACCTCARD-COMBO` sentence from
  //       `app/cbl/COCRDSLC.cbl` L153-L154, which is the branch it transcribes -- the search condition
  //       named no card.
  await waitForBandToReport(MESSAGES.DID_NOT_FIND_ACCTCARD_COMBO.text);
  notFound.unmount();

  vi.mocked(getCard).mockRejectedValue(apiError({ status: 403 }));
  const refused = await act(
    /**
     * Renders the subject at a card address whose read is refused for authority.
     * @returns {Promise<HarnessRenderResult>} The rendered tree and its operator.
     */
    async (): Promise<HarnessRenderResult> =>
      await renderInAppShell(<CardDetailScreen />, {
        routePath: CARD_DETAIL_ROUTE,
        initialEntries: [cardDetailPath(A_SELECTOR)],
      }),
  );

  // WHY : Assumptions: 403 takes the additive access-denied sentence rather than the baseline's
  //       administrator-only refusal, because both operations this screen calls require only
  //       `carddemo-user` authority -- so a refusal means the token carries neither CardDemo group,
  //       and naming administrative rights would point the operator at authority they must not be
  //       granted.
  await waitForBandToReport(ACCESS_DENIED_NOT_AUTHORIZED);
  refused.unmount();
}

/**
 * A service fault reports a sentence and never the composed diagnostic behind it.
 * @returns {Promise<void>} Resolves once the sentence is present and the diagnostic absent.
 */
async function withholdsTheComposedFileDiagnostic(): Promise<void> {
  vi.mocked(getCard).mockRejectedValue(apiError({ status: 500 }));
  await act(
    /**
     * Renders the subject at a card address whose read fails inside the service.
     * @returns {Promise<HarnessRenderResult>} The rendered tree and its operator.
     */
    async (): Promise<HarnessRenderResult> =>
      await renderInAppShell(<CardDetailScreen />, {
        routePath: CARD_DETAIL_ROUTE,
        initialEntries: [cardDetailPath(A_SELECTOR)],
      }),
  );

  // WHY : Assumptions: a 5xx takes `XREF-READ-ERROR` from `app/cbl/COCRDSLC.cbl` L155-L156, which is
  //       the closest sentence this program has to "the card file read failed for a reason other than
  //       absence". The condition name is declared and never `SET` in the baseline, so the target
  //       reaches a sentence the reference could not -- recorded here rather than left to look like a
  //       transcription of a live branch.
  await waitForBandToReport(MESSAGES.XREF_READ_ERROR.text);
  // WHY : ⚠️ Assumptions: the composed diagnostic is absent from the whole document and not merely
  //       from the band. `app/cbl/COCRDSLC.cbl` L102-L121 appends an operation name, an internal file
  //       name and the CICS response and reason codes to its prefix at L104, and the register in
  //       `ui/src/messages/messages.ts` withholds exactly that class of detail at its other sites.
  expect(document.body.textContent).not.toContain(THE_WITHHELD_DIAGNOSTIC_PREFIX);
}

/**
 * Registers the message-fidelity cases.
 * @returns {void} Nothing; registration is the effect.
 */
function messageFidelityCases(): void {
  it('cites the source line of every catalogue entry', citesTheSourceLineOfEveryCatalogueEntry);
  it(
    'preserves the trailing padding of the exit sentence',
    preservesTheTrailingPaddingOfTheExitSentence,
  );
  it(
    'preserves the four dots of the provisional sentence',
    preservesTheFourDotsOfTheProvisionalSentence,
  );
  it(
    'keeps both account sentences despite their identical wording',
    keepsBothAccountSentencesDespiteTheirIdenticalWording,
  );
  it('keeps the uppercase abend sentence uppercase', keepsTheUppercaseAbendSentenceUppercase);
  it(
    'paints the prompt for input on the search arrival',
    paintsThePromptForInputOnTheSearchArrival,
  );
  it(
    'confirms a retrieved record on the information line',
    confirmsARetrievedRecordOnTheInformationLine,
  );
  it(
    'reports no input received when neither field was supplied',
    reportsNoInputReceivedWhenNeitherFieldWasSupplied,
  );
  it("reports the missing field's own prompt", reportsTheMissingFieldsOwnPrompt);
  it(
    'reports the catalogue sentence each status selects',
    reportsTheCatalogueSentenceEachStatusSelects,
  );
  it('withholds the composed file diagnostic', withholdsTheComposedFileDiagnostic);
}

describe('card detail message fidelity', messageFidelityCases);

/**
 * The legend paints exactly the two keys this mapset's own legend field names.
 * @returns {Promise<void>} Resolves once the legend has been read and compared.
 */
async function paintsExactlyTheTwoKeysTheMapsetNames(): Promise<void> {
  await renderAtACardAddress();

  // WHY : ⚠️ Assumptions: two controls and no more, because two independent measurements of the
  //       reference agree on the same pair. `app/bms/COCRDSL.bms` L148-L152 paints one `FKEYS` field
  //       whose `INITIAL=` value is `'ENTER=Search Cards  F3=Exit'`, and
  //       `app/cbl/COCRDSLC.cbl` L292-L293 admits exactly `CCARD-AID-ENTER` and `CCARD-AID-PFK03`.
  //       The painted legend and the accepted key set therefore agree, and together they are the whole
  //       contract -- a third control here would advertise a key the program cannot act on.
  expect(legendControlNames()).toStrictEqual(['ENTER=Search Cards', 'F3=Exit']);
  // WHY : ⚠️ Assumptions: the two labels rejoined with TWO spaces reproduce the mapset literal
  //       byte-for-byte. The doubled space is content rather than formatting -- it is what separated
  //       the two descriptors inside one 75-character `FKEYSI` field (`app/cpy-bms/COCRDSL.CPY` L108)
  //       -- so rejoining is what proves the split preserved the whole value rather than an
  //       approximation of it.
  expect(legendControlNames().join('  ')).toBe('ENTER=Search Cards  F3=Exit');
  // WHY : Assumptions: this screen labels Enter `Search Cards`, not `Continue` or `Process`. The
  //       labels are measured per mapset precisely because they differ this much across screens, which
  //       is why the key bar takes a per-screen descriptor array rather than a uniform table.
  expect(legendControlNames()[0]).toContain('Search Cards');
}

/**
 * The legend renders the whole of the mapset's legend field within its declared width.
 * @returns {Promise<void>} Resolves once the rendered legend has been measured.
 */
async function keepsTheLegendWithinItsDeclaredWidth(): Promise<void> {
  await renderAtACardAddress();

  // WHY : Assumptions: 75 characters, from `FKEYSI PIC X(75)` at `app/cpy-bms/COCRDSL.CPY` L108 and
  //       `FKEYS LENGTH=75` at `app/bms/COCRDSL.bms` L148-L151. This mapset is unusual in declaring a
  //       DEDICATED legend field at all, which is why the key bar cites this symbolic map as a source;
  //       the assertion is that the migrated legend still fits the field that carried it.
  expect(legendControlNames().join('  ').length).toBeLessThanOrEqual(DECLARED_WIDTHS.keyLegend);
}

/**
 * Enter is the primary action and the exit key is an ordinary one.
 * @returns {Promise<void>} Resolves once both variants have been asserted.
 */
async function marksEnterPrimaryAndTheExitKeyOrdinary(): Promise<void> {
  await renderAtACardAddress();

  // WHY : Assumptions: the membership test comes from `PRIMARY_ACTION_AIDS` in
  //       `ui/src/layout/PfKeyBar.tsx` rather than from a literal, so this case asserts the bar applied
  //       its own published rule to this screen's bindings instead of restating the rule. Enter is a
  //       primary action and PF3 is not, which is the emphasis split the target design specifies.
  expect(PRIMARY_ACTION_AIDS).toContain('ENTER');
  expect(PRIMARY_ACTION_AIDS).not.toContain('PFK03');
  // WHY : Assumptions: the variant is read off the class the design system emits for its primary
  //       button, because a prop is not otherwise observable in a rendered tree. No colour, radius or
  //       spacing value is asserted anywhere here -- those resolve through the theme in
  //       `ui/src/theme/tokens.ts`, and a literal would be exactly the hardcoded design value the
  //       token bridge exists to prevent.
  expect(legendControlFor('Enter').classList.contains('ant-btn-primary')).toBe(true);
  expect(legendControlFor('F3').classList.contains('ant-btn-primary')).toBe(false);
}

/**
 * A real Enter key press takes the search turn.
 *
 * Assumptions: the observable is the resolution call, because that is the request the turn issues --
 * `9100-GETCARD-BYACCTCARD` reads by the card number alone, its account move having been commented out
 * at `app/cbl/COCRDSLC.cbl` L740-L741.
 * @returns {Promise<void>} Resolves once the resolution has been observed.
 */
async function takesTheSearchTurnFromARealKeyPress(): Promise<void> {
  vi.mocked(lookupCard).mockResolvedValue(aCard());
  const rendered = await renderTheSearchArrival();

  await fillTheSearchFields(rendered, AN_ACCOUNT_NUMBER, A_CARD_NUMBER, 'account');
  await takeTheTurn(rendered, 'ENTER');

  // WHY : ⚠️ Assumptions: the turn is driven by a GENUINE keyboard event and not by calling a handler,
  //       because the 3270 original was keyboard-only and so the PF-key contract is a keyboard
  //       contract. A case that only clicked the legend would pass in full while every keyboard
  //       binding in the application was broken.
  expect(vi.mocked(lookupCard)).toHaveBeenCalledWith(A_CARD_NUMBER);
}

/**
 * The legend control takes the same search turn the key press takes.
 * @returns {Promise<void>} Resolves once the same resolution has been observed.
 */
async function takesTheSameSearchTurnFromTheLegendControl(): Promise<void> {
  vi.mocked(lookupCard).mockResolvedValue(aCard());
  const rendered = await renderTheSearchArrival();

  await fillTheSearchFields(rendered, AN_ACCOUNT_NUMBER, A_CARD_NUMBER, 'account');
  await takeTheTurnFromTheLegend(rendered, 'Enter');

  // WHY : ⚠️ Alternatives Considered: asserting only ONE of the two drivers. Rejected in both
  //       directions: the key press alone would leave the rendered legend unverified, and the click
  //       alone would verify the bar rather than the binding. Both are asserted because the bar exists
  //       to make a keyboard-only workflow discoverable WITHOUT taking it away, so the two must reach
  //       the same dispatch -- and the identical expectation here is what says so.
  expect(vi.mocked(lookupCard)).toHaveBeenCalledWith(A_CARD_NUMBER);
}

/**
 * The exit key leaves this screen, from the keyboard and from the legend alike.
 *
 * Assumptions: the observable is the DEPARTURE of the record rather than a router assertion, because
 * the harness mounts this screen at its own route only -- so a navigation to the browse address matches
 * no child route and the subject unmounts. That is a faithful reading of the outcome: PF3 returns to
 * the caller, which `app/cbl/COCRDSLC.cbl` L305-L333 resolves to the list screen this route's only
 * caller is.
 *
 * Trade-offs: the router therefore reports `No routes matched location "/cards"` on the error stream
 * twice while this case runs, and that line is expected rather than a defect. It is the same fact the
 * assertion below reads -- the operator left for an address this one-route harness does not mount -- and
 * the alternative was to assemble a second route here, which would mean hand-building the provider
 * stack that `./setup` exists to stop each file rebuilding. A departure asserted through the real frame
 * with one stray informational line is worth more than one asserted against a tree the application
 * never builds.
 * @returns {Promise<void>} Resolves once both drivers have left the screen.
 */
async function leavesTheScreenOnTheExitKeyFromEitherDriver(): Promise<void> {
  const first = await renderAtACardAddress();
  await screen.findByText(A_MASKED_RENDERING);

  await takeTheTurn(first, 'PFK03');
  await waitForTheRecordToLeave();
  first.unmount();

  const second = await renderAtACardAddress();
  await screen.findByText(A_MASKED_RENDERING);

  await takeTheTurnFromTheLegend(second, 'F3');
  await waitForTheRecordToLeave();
}

/**
 * Waits until the record view is no longer mounted.
 * @returns {Promise<void>} Resolves once the masked rendering has gone from the document.
 */
async function waitForTheRecordToLeave(): Promise<void> {
  await waitFor(
    /**
     * Asserts the record view has left the document.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(screen.queryByText(A_MASKED_RENDERING)).toBeNull();
    },
  );
}

/**
 * An unbound key is coerced into the Enter arm and reports nothing of its own.
 * @returns {Promise<void>} Resolves once the coercion and the silence have been asserted.
 */
async function coercesAnUnboundKeyIntoTheEnterArmSilently(): Promise<void> {
  const rendered = await renderAtACardAddress();
  await screen.findByText(A_MASKED_RENDERING);
  vi.mocked(getCard).mockClear();

  await takeTheTurn(rendered, 'PFK05');

  // WHY : ⚠️ Assumptions: an unrecognised key RE-RUNS the Enter arm rather than being ignored, because
  //       that is what the program does: `app/cbl/COCRDSLC.cbl` L291-L299 sets its invalid-key flag and
  //       then `SET CCARD-AID-ENTER TO TRUE`, so the press is coerced and falls into the same path. On
  //       this arrival the Enter arm re-reads the record on display, so the read call is the observable.
  await waitFor(
    /**
     * Asserts the coerced arm re-read the record.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(vi.mocked(getCard)).toHaveBeenCalledWith(A_SELECTOR);
    },
  );
  // WHY : ⚠️ Assumptions: NO invalid-key sentence is shown, and its absence is measured rather than
  //       assumed. An exhaustive search of the reference finds no `CCDA-MSG-INVALID-KEY` move anywhere
  //       in `app/cbl/COCRDSLC.cbl` -- this is one of six programs that never emit it, alongside
  //       `COACTVWC`, `COACTUPC`, `COCRDLIC`, `COCRDUPC` and `COTRTLIC` -- so asserting one here would
  //       be a fabrication, and asserting its ABSENCE is what pins the divergence from the sign-on
  //       screen, whose own `WHEN OTHER` branch does move it.
  expect(document.body.textContent).not.toContain(INVALID_KEY_PRESSED.trim());
  // WHY : Assumptions: the legend is unchanged by the press, so no key gained a binding by being
  //       pressed. Together with the two-control assertion above, this is the whole of "no other key is
  //       bound on this screen".
  expect(legendControlNames()).toHaveLength(2);
}

/**
 * Registers the key-contract cases.
 * @returns {void} Nothing; registration is the effect.
 */
function keyContractCases(): void {
  it('paints exactly the two keys the mapset names', paintsExactlyTheTwoKeysTheMapsetNames);
  it('keeps the legend within its declared width', keepsTheLegendWithinItsDeclaredWidth);
  it('marks enter primary and the exit key ordinary', marksEnterPrimaryAndTheExitKeyOrdinary);
  it('takes the search turn from a real key press', takesTheSearchTurnFromARealKeyPress);
  it(
    'takes the same search turn from the legend control',
    takesTheSameSearchTurnFromTheLegendControl,
  );
  it(
    'leaves the screen on the exit key from either driver',
    leavesTheScreenOnTheExitKeyFromEitherDriver,
  );
  it(
    'coerces an unbound key into the enter arm silently',
    coercesAnUnboundKeyIntoTheEnterArmSilently,
  );
}

describe('card detail key contract', keyContractCases);

/**
 * An ordinary operator sees the account number masked to its last four digits.
 *
 * ⚠️ Assumptions: the session is established ONLY through the harness's identity helper, which mints a
 * token carrying the group and drives the real sign-on exchange. A test cannot grant itself authority
 * by any shorter route and none is invented here: `ui/src/hooks/useAuth.ts` holds its session in module
 * scope and deliberately publishes no setter for the groups or the user type, and there is no context
 * provider anywhere to wrap. That is the point rather than an inconvenience -- authority derives from
 * the SIGNED `cognito:groups` claim, so a helper that assigned a group directly would create a second,
 * weaker path to authority that production does not have, and every assertion made through it would be
 * about a system nobody deploys.
 * @returns {Promise<void>} Resolves once the masked rendering has been asserted.
 */
async function masksTheAccountNumberForAnOrdinaryOperator(): Promise<void> {
  await seedSession({ groups: [CARDDEMO_USER_GROUP] });

  await renderTheRecordUnframed();

  const rendering = await screen.findByText(A_MASKED_RENDERING);
  // WHY : Assumptions: the pattern is imported from `ui/src/api/masking.ts` rather than written here,
  //       so this asserts the form the contract itself requires -- twelve mask characters then the last
  //       four digits of a `PIC X(16)` number (`app/cpy/CVACT02Y.cpy` L5).
  expect(MASKED_CARD_NUMBER.test(nameOfControl(rendering))).toBe(true);
  expectNoWholeCardNumberAnywhere();
}

/**
 * An administrative operator sees the same masked rendering, and no whole number is fetched.
 *
 * ⚠️ Trade-offs: this is the documented divergence, and it is a NARROWING of the disclosure the target
 * design permits rather than a shortfall. The design places the primary account number under a masking
 * rule with exactly one exception -- "masked to last four except on the administrative detail endpoint"
 * -- and this is the card-detail screen, so this is the one screen in the application where an unmasked
 * number could legitimately render. It does not: the screen imports only `getCard` and `lookupCard`, and
 * `getAdminCardDetail`, which is the sole operation permitted to publish a whole number, is called by no
 * screen in the bundle. So the exception exists in the client surface and is deliberately unwired, and
 * this case asserts the posture as built rather than the posture as permitted.
 *
 * ⚠️ Assumptions: the exception, where it is ever wired, is gated on the signed group claim and never on
 * anything the client asserts about itself -- which is precisely what the elimination of the
 * pseudo-conversational communication area bought. In the reference the user type travelled in
 * `CDEMO-USER-TYPE PIC X(01)` (`app/cpy/COCOM01Y.cpy` L26-L28), storage the client echoed back, so a
 * client could in principle assert its own type; here it can assert nothing, because the group arrives
 * inside a token the service signed. The route reinforces it from the other side: the address carries a
 * sealed selector rather than the number, so an unmasked value cannot leak through a history entry, a
 * referrer or an access log even for an administrator.
 * @returns {Promise<void>} Resolves once the masked rendering and the unwired operation are asserted.
 */
async function masksTheAccountNumberForAnAdministratorToo(): Promise<void> {
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });

  await renderTheRecordUnframed();

  const rendering = await screen.findByText(A_MASKED_RENDERING);
  expect(MASKED_CARD_NUMBER.test(nameOfControl(rendering))).toBe(true);
  // WHY : Assumptions: the administrative read is asserted NOT to have been called, which is the
  //       observable form of "this screen does not reach the exception". Asserting only the rendered
  //       value would pass equally if the screen had fetched a whole number and then masked it itself
  //       -- and re-masking a value client-side is the failure mode the screen's own note rejects,
  //       because it would hide a server-side rendering fault the client is positioned to report.
  expect(vi.mocked(getAdminCardDetail)).not.toHaveBeenCalled();
  expectNoWholeCardNumberAnywhere();
}

/**
 * Asserts that no rendered text carries a whole sixteen-digit card number.
 *
 * Assumptions: the check is a run of sixteen digits anywhere in the document text rather than one
 * fixture value, so it catches an unmasked number the screen composed as well as one it was handed.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function expectNoWholeCardNumberAnywhere(): void {
  const rendered = document.body.textContent ?? '';
  expect(/[0-9]{16}/u.test(rendered)).toBe(false);
}

/**
 * The record view exposes exactly the five fields the mapset paints, and nothing further.
 *
 * ⚠️ Assumptions: the three-digit verification value the card record declares at
 * `app/cpy/CVACT02Y.cpy` L7 -- between the account identifier and the embossed name -- is returned by
 * NO operation of the card contract and declared by no shape in `ui/src/api/types.ts`, so no fixture in
 * this file can carry one and no rendering can disclose one. The prohibition is absolute and has no
 * administrative exception anywhere in the application, which is what distinguishes it from the masking
 * rule above: masking has one permitted exception, this has none. The assertion is therefore the
 * completeness of the record view -- five value cells, being the five fields the mapset paints -- so a
 * sixth field appearing here fails this case whatever it holds.
 * @returns {Promise<void>} Resolves once the record view's completeness has been asserted.
 */
async function exposesOnlyTheFiveFieldsTheMapsetPaints(): Promise<void> {
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });

  await renderTheRecordUnframed();
  await screen.findByText(A_MASKED_RENDERING);

  expect(recordLabelCells()).toHaveLength(RECORD_LABEL_COUNT);
  // WHY : Assumptions: the label of every cell is one of the five the mapset paints, read from
  //       `app/bms/COCRDSL.bms` L83, L95, L106, L115 and L125. Counting alone would pass if a cell had
  //       been swapped for a field the terminal never showed, so the identities are checked too.
  const labels = recordLabelCells().map(textOfElement);
  for (const label of labels) {
    expect(THE_FIVE_PAINTED_LABELS.some(matchesCollapsed(label))).toBe(true);
  }
}

/**
 * The five value labels the mapset paints, in the order it paints them.
 *
 * Assumptions: these are `INITIAL=` literals of the mapset itself, so they live beside the screen that
 * paints them rather than in the message catalog -- the catalog's own boundary excludes a field label,
 * which is positional and meaningless apart from the control beside it. They are compared with interior
 * whitespace collapsed, because the mapset pads each one to a fixed cell width so its colons line up
 * down the column and a browser collapses that padding when it renders.
 */
const THE_FIVE_PAINTED_LABELS = [
  'Account Number    :',
  'Card Number       :',
  'Name on card      :',
  'Card Active Y/N   : ',
  'Expiry Date       : ',
] as const;

/**
 * Reads one element's text.
 * @param {Element} element - One rendered element.
 * @returns {string} Its text content, trimmed.
 */
function textOfElement(element: Element): string {
  return (element.textContent ?? '').trim();
}

/**
 * Builds a predicate matching one rendered label against a painted literal, whitespace collapsed.
 * @param {string} rendered - The label as the document holds it.
 * @returns {(painted: string) => boolean} A predicate over the painted literals.
 */
function matchesCollapsed(rendered: string): (painted: string) => boolean {
  /**
   * Compares one painted literal against the rendered label.
   * @param {string} painted - One `INITIAL=` literal from the mapset.
   * @returns {boolean} `true` when the two agree once interior whitespace is collapsed.
   */
  return function comparesOneLiteral(painted: string): boolean {
    return collapse(painted) === collapse(rendered);
  };
}

/**
 * Collapses interior whitespace runs and trims the ends of one string.
 * @param {string} value - The string to normalise.
 * @returns {string} The normalised string.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Registers the account-number posture cases.
 * @returns {void} Nothing; registration is the effect.
 */
function accountNumberPostureCases(): void {
  it(
    'masks the account number for an ordinary operator',
    masksTheAccountNumberForAnOrdinaryOperator,
  );
  it(
    'masks the account number for an administrator too',
    masksTheAccountNumberForAnAdministratorToo,
  );
  it('exposes only the five fields the mapset paints', exposesOnlyTheFiveFieldsTheMapsetPaints);
}

describe('card detail account number posture', accountNumberPostureCases);

/**
 * The selection reaches the client from the route parameter and from nowhere else.
 *
 * ⚠️ Refactoring Rationale: the reference carried the selection in the communication area -- the
 * account identifier at `app/cpy/COCOM01Y.cpy` L38 as `CDEMO-ACCT-ID PIC 9(11)` and the card number at
 * L41 as `CDEMO-CARD-NUM PIC 9(16)` -- which was storage the client echoed back between pseudo-
 * conversational turns. It is a path parameter here, and the difference is not tidiness: a request that
 * names its own subject is self-describing, so the service can authorise THIS request against the token
 * that carries it, where a value recovered from echoed storage can only be trusted as far as the echo.
 * No server-side session field carries the selection in the target, and this case asserts the positive
 * half of that -- the address alone determines which row is read.
 * @returns {Promise<void>} Resolves once the read has been observed with the address's own value.
 */
async function readsTheSelectionFromTheRouteParameter(): Promise<void> {
  await renderTheRecordUnframed();
  await screen.findByText(A_MASKED_RENDERING);

  // WHY : Assumptions: called exactly once and with the address's own parameter, so the value the
  //       client received is traceable to the route rather than to any state the screen held. The
  //       parameter is named `cardKey` by `CARD_DETAIL_ROUTE` in `ui/src/routes/cards.ts`, which
  //       `ui/src/router.tsx` mounts this screen at; the name is imported rather than spelled here so
  //       the two cannot drift.
  expect(vi.mocked(getCard)).toHaveBeenCalledTimes(1);
  expect(vi.mocked(getCard)).toHaveBeenCalledWith(A_SELECTOR);
  expect(vi.mocked(lookupCard)).not.toHaveBeenCalled();
}

/**
 * The address carries a sealed selector and never the card number it stands for.
 *
 * ⚠️ Assumptions: this is registered divergence `D-CARD-SELECTOR` and it is the reason the route
 * parameter is `:cardKey` rather than a card number. A browser path is retained in places neither this
 * bundle nor the service can redact -- the edge access log composed from the request line before any
 * application code runs, the browser's own history, an outgoing referrer -- so a number in the path
 * would write one durable copy of a primary account number per card view, forever. A selector carries
 * no number at all, which is a property no logging configuration has to be trusted for.
 * @returns {Promise<void>} Resolves once the address's shape has been asserted both ways.
 */
async function carriesASealedSelectorInTheAddressAndNotTheNumber(): Promise<void> {
  await renderTheRecordUnframed();
  await screen.findByText(A_MASKED_RENDERING);

  // WHY : Assumptions: both directions are asserted from the route contract's own guards. A run of
  //       digits is itself valid URL-safe base64, so "is a selector" alone would not exclude a card
  //       number -- it is excluded by length, which `CARD_SELECTOR_LENGTH` fixes at 59 against the
  //       number's sixteen.
  expect(isCardSelector(A_SELECTOR)).toBe(true);
  expect(isCardNumber(A_SELECTOR)).toBe(false);
  expect(cardDetailPath(A_SELECTOR)).not.toContain(A_CARD_NUMBER);
}

/**
 * A typed card number is resolved through the lookup call rather than through the address.
 *
 * Assumptions: the read key is the card number ALONE even though both fields are required, which is
 * the reference's own behaviour rather than an omission: `9100-GETCARD-BYACCTCARD` moves only
 * `CC-CARD-NUM` into its record identification field and the account move immediately above it is
 * commented out (`app/cbl/COCRDSLC.cbl` L740-L741), while the by-account paragraph
 * `9150-GETCARD-BYACCT` at L779 is performed from nowhere. The account field is still validated, so an
 * account-only search is impossible there and here.
 *
 * Assumptions: the by-account path the reference reserves is the `CARDAIX` alternate index -- the
 * cards-by-account access path CICS surfaces as its own file, which the migration makes explicit as the
 * non-unique secondary index `idx_cards_account_id`. It is an ACCESS PATH rather than decoration, which
 * is why it survives as a real index rather than as a comment.
 * @returns {Promise<void>} Resolves once the resolution has been observed carrying the number.
 */
async function resolvesATypedNumberThroughTheLookupCall(): Promise<void> {
  vi.mocked(lookupCard).mockResolvedValue(aCard());
  const rendered = await renderTheSearchArrivalUnframed();

  await fillTheSearchFields(rendered, AN_ACCOUNT_NUMBER, A_CARD_NUMBER, 'account');
  await takeTheTurn(rendered, 'ENTER');

  await waitFor(
    /**
     * Asserts the resolution carried the typed number.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(vi.mocked(lookupCard)).toHaveBeenCalledWith(A_CARD_NUMBER);
    },
  );
  // WHY : Assumptions: the number travels in the LOOKUP call and not in an address, which is the other
  //       half of the selector design -- the one value an operator types is carried in a request body,
  //       and the reply's own selector is what the address is then built from.
  expect(vi.mocked(getCard)).not.toHaveBeenCalledWith(A_CARD_NUMBER);
}

/**
 * Registers the selection-context cases.
 * @returns {void} Nothing; registration is the effect.
 */
function selectionContextCases(): void {
  it('reads the selection from the route parameter', readsTheSelectionFromTheRouteParameter);
  it(
    'carries a sealed selector in the address and not the number',
    carriesASealedSelectorInTheAddressAndNotTheNumber,
  );
  it('resolves a typed number through the lookup call', resolvesATypedNumberThroughTheLookupCall);
}

describe('card detail selection context', selectionContextCases);

/**
 * A refused field carries the design system's error treatment and an accepted one does not.
 *
 * Assumptions: the treatment transcribes the templated highlight in `app/cpy/CSSETATY.cpy` L17-L27,
 * which moves `DFHRED` into the field's own colour subfield when that field's validation flag is
 * not-OK or blank. `Form.Item validateStatus="error"` is the target of that move, and the colour it
 * resolves to comes from `FIELD_ERROR_TOKENS` in `ui/src/theme/tokens.ts` -- so no colour value appears
 * in this case, only the state that selects one.
 * @returns {Promise<void>} Resolves once the refused and accepted fields have been distinguished.
 */
async function marksTheRefusedFieldAndLeavesTheAcceptedOneAlone(): Promise<void> {
  const rendered = await renderTheSearchArrivalUnframed();

  await fillTheSearchFields(rendered, '', A_CARD_NUMBER, 'account');
  await takeTheTurn(rendered, 'ENTER');

  const { account, card } = searchFields();
  await waitFor(
    /**
     * Asserts the refused field reached its error state.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(isFieldRefused(account)).toBe(true);
    },
  );
  // WHY : ⚠️ Assumptions: the ACCEPTED field carries no error state, which is the half of the contract
  //       a blanket highlight would break. `app/cbl/COCRDSLC.cbl` L533-L539 colours a field only when
  //       its own flag is set, and the card filter's flag is `FLG-CARDFILTER-ISVALID` on this turn --
  //       so highlighting both would tell the operator to correct a field the program accepted.
  expect(isFieldRefused(card)).toBe(false);
  // WHY : Assumptions: the control also reports itself invalid to assistive technology, which is the
  //       accessibility equivalent of the colour move -- the 3270 conveyed the refusal by colour alone,
  //       and colour alone is not conveyable to a screen reader.
  expect(account).toHaveAttribute('aria-invalid', 'true');
  expect(card).toHaveAttribute('aria-invalid', 'false');
}

/**
 * A blank field additionally receives the literal marker the reference writes into it.
 *
 * ⚠️ Assumptions: the marker goes into the CONTROL'S OWN VALUE and not into a decoration beside it,
 * because that is what the reference does and the difference is observable on the NEXT turn.
 * `app/cpy/CSSETATY.cpy` L23-L26 moves `'*'` into the field's output subfield when the field is blank,
 * `app/cbl/COCRDSLC.cbl` L543 and L549 perform exactly that move for the two fields here, and
 * `2200-EDIT-MAP-INPUTS` then reads the marker back as INPUT at L615 and L622 before it tests anything
 * else. A marker synthesised at render time would show the same glyph while leaving the control empty,
 * so an operator typing after a refusal would be editing a value that had never held what they could
 * see.
 * @returns {Promise<void>} Resolves once both controls hold the marker.
 */
async function writesTheBlankMarkerIntoTheControlItself(): Promise<void> {
  const rendered = await renderTheSearchArrivalUnframed();

  await takeTheTurn(rendered, 'ENTER');

  const { account, card } = searchFields();
  await waitFor(
    /**
     * Asserts the account control holds the blank marker.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(account).toHaveValue(FIELD_ERROR_TOKENS.blankMarker);
    },
  );
  // WHY : Assumptions: the marker is read from `FIELD_ERROR_TOKENS.blankMarker` rather than written as
  //       a literal, because it is a design value the theme module owns -- it is the one place the
  //       reference's field-error contract writes both a colour and a value, and both belong there.
  expect(card).toHaveValue(FIELD_ERROR_TOKENS.blankMarker);
}

/**
 * The refusal sentence is reported once, on the screen's message line and not beneath the control.
 *
 * Assumptions: one message channel and not two. `app/cbl/COCRDSLC.cbl` L532 moves the single sentence
 * into the screen's message field while L533-L539 gives the field only a colour, so repeating the
 * sentence as help text under the control would print one refusal twice. This is a deliberate departure
 * from the design system's habit of pairing `validateStatus` with `help`.
 * @returns {Promise<void>} Resolves once the sentence has been counted.
 */
async function reportsTheRefusalSentenceExactlyOnce(): Promise<void> {
  const rendered = await renderTheSearchArrival();

  await fillTheSearchFields(rendered, '', A_CARD_NUMBER, 'account');
  await takeTheTurn(rendered, 'ENTER');

  const band = await screen.findByTestId(MESSAGE_BAND_TEST_ID);
  await waitFor(
    /**
     * Asserts the band carries the field's own prompt.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(band).toHaveTextContent(MESSAGES.WS_PROMPT_FOR_ACCT.text);
    },
  );
  // WHY : Assumptions: exactly one carrier of the sentence in the whole document. A `help` slot under
  //       the control would make this two, which is the count that distinguishes this screen's
  //       single-channel treatment from the two-channel one the design system offers by default.
  expect(screen.getAllByText(MESSAGES.WS_PROMPT_FOR_ACCT.text)).toHaveLength(1);
}

/**
 * The highlight appears on the first refused turn, there being no re-entry flag to gate it.
 *
 * ⚠️ Refactoring Rationale: the reference gates the highlight on a THIRD condition beyond the field's
 * own flags -- `AND CDEMO-PGM-REENTER` at `app/cpy/CSSETATY.cpy` L20, reading the re-entry
 * discriminator `CDEMO-PGM-CONTEXT` declared at `app/cpy/COCOM01Y.cpy` L29-L31. That discriminator
 * disappears entirely in the target: a stateless handler answering with a field-error array has no
 * first-entry-versus-re-entry distinction to make, because the response body it renders describes the
 * turn it belongs to. So the highlight is driven purely by the outcome, and there is no flag for this
 * case to arrange or assert -- which is exactly what makes it assertable on the FIRST turn of a fresh
 * mount, where the reference would have withheld it.
 * @returns {Promise<void>} Resolves once the first turn's highlight has been observed.
 */
async function highlightsOnTheFirstTurnWithNoReEntryFlag(): Promise<void> {
  const rendered = await renderTheSearchArrivalUnframed();

  const { account } = searchFields();
  expect(isFieldRefused(account)).toBe(false);

  await takeTheTurn(rendered, 'ENTER');

  await waitFor(
    /**
     * Asserts the refusal is rendered on the very first turn.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(isFieldRefused(searchFields().account)).toBe(true);
    },
  );
}

/**
 * Registers the field-error contract cases.
 * @returns {void} Nothing; registration is the effect.
 */
function fieldErrorContractCases(): void {
  it(
    'marks the refused field and leaves the accepted one alone',
    marksTheRefusedFieldAndLeavesTheAcceptedOneAlone,
  );
  it('writes the blank marker into the control itself', writesTheBlankMarkerIntoTheControlItself);
  it('reports the refusal sentence exactly once', reportsTheRefusalSentenceExactlyOnce);
  it(
    'highlights on the first turn with no re-entry flag',
    highlightsOnTheFirstTurnWithNoReEntryFlag,
  );
}

describe('card detail field error contract', fieldErrorContractCases);
