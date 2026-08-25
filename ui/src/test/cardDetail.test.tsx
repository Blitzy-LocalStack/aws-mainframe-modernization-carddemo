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
import { APP_SHELL_TEST_ID, SHELL_PINNED_ZONE_TEST_ID } from '../layout/AppShell';
import { BUSY_ANNOUNCEMENT_TEST_ID } from '../layout/fieldHelp';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL, PRIMARY_ACTION_AIDS } from '../layout/PfKeyBar';
import {
  ACCESS_DENIED_NOT_AUTHORIZED,
  CARD_DETAIL_INVALID_LINK_GUIDANCE,
  INVALID_KEY_PRESSED,
  REQUEST_IN_PROGRESS,
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
import { BMS_TEXT_COLOR_TOKENS, FIELD_ERROR_TOKENS } from '../theme/tokens';
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
import { KEYLESS_ENTRY_ROUTES } from '../router';
import { CARD_DETAIL_TITLE, CardDetailScreen } from '../screens/cardDetail/index';

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

/**
 * How many value labels the mapset paints in its RECORD zone.
 *
 * ⚠️ Refactoring Rationale: three, and it was five. The two it lost are the account number and the card
 * number, which the record view rendered a second time beside the criteria controls that already hold
 * them -- a browser measured each identifier twice on one screen and in two different typefaces. The
 * mapset declares one field per identifier and both are criteria fields: `ACCTSID` at `POS=(7,45)`
 * (`app/bms/COCRDSL.bms` L84-L88) and `CARDSID` at `POS=(8,45)` (L96-L100). Its record zone declares
 * `CRDNAME` at `POS=(11,25)` (L107-L109), `CRDSTCD` at `POS=(13,25)` (L116-L119) and the
 * `EXPMON`/`EXPYEAR` pair at `POS=(15,25)` and `POS=(15,30)` (L126-L136), rendered as one expiry value
 * -- three, and no more. The program agrees: `1200-SETUP-SCREEN-VARS` moves the retrieved keys into the
 * two criteria fields at `app/cbl/COCRDSLC.cbl` L463 and L471 and only the other four values into the
 * record fields at L475-L484.
 *
 * Assumptions: the count is kept as a named constant rather than inlined, because two cases assert it
 * and a fourth cell appearing in either is the same regression.
 */
const RECORD_LABEL_COUNT = 3;

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
 * Assumptions: the unframed arrangement is used for the same reason it is above -- these cases assert
 * on the screen's own controls and never on a delegated zone, so mounting the frame would double the
 * tree for nothing.
 *
 * ⚠️ Refactoring Rationale: what this helper AWAITS is the screen's own controls, where it used to await
 * the row-20 information band. That band is no longer this screen's to paint: it is published through
 * the frame's row-22 channel, because a band composed inside the scrolling body is painted underneath
 * the frame's sticky pinned zone and can be occluded outright -- the measurement is recorded at the
 * publication in `ui/src/screens/cardDetail/index.tsx`. An unframed arrangement therefore has no
 * renderer for it, and waiting on it here would hang every case that takes this arrangement. The
 * controls are the right thing to wait for regardless: they are what these cases go on to assert
 * against, so the wait now settles exactly what the case needs.
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
  await screen.findAllByRole('textbox');
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
 * Waits until the retrieved record is on the glass.
 *
 * ⚠️ Refactoring Rationale: the anchor is the EMBOSSED NAME, and every caller of this helper used to
 * wait on the masked card rendering instead. That rendering is no longer text the record view paints:
 * the record's two identity rows are deleted, because the mapset declares one field per identifier and
 * both are the criteria controls (`app/bms/COCRDSL.bms` L84-L88 and L96-L100), so the masked value now
 * lives ONLY in a control's `value` -- which `getByText` cannot see, since an input's value is not part
 * of its text content. The embossed name is the natural replacement: `CRDNAME` at `POS=(11,25)`
 * (L107-L109) is the first field of the record zone and is rendered as text, so its presence means the
 * read resolved and the record view mounted.
 *
 * Assumptions: this is a wait and not an assertion, so it is expressed through `findByText` rather than
 * a `waitFor` wrapping an expectation -- the query throws with the document attached when the record
 * never arrives, which is the diagnostic a caller wants.
 * @returns {Promise<void>} Resolves once the record view has painted.
 */
async function waitForTheRecordView(): Promise<void> {
  await screen.findByText(A_NAME_AT_THE_DECLARED_WIDTH);
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
 * Reads one input control's value as the string it is.
 *
 * ⚠️ Assumptions: the value is read WITHOUT trimming, unlike {@link nameOfControl}, because the two
 * identifiers this is used on are held at fixed declared widths -- `ACCTSIDI PIC X(11)` and `CARDSIDI
 * PIC X(16)` (`app/cpy-bms/COCRDSL.CPY` L60 and L66) -- and a width assertion over a trimmed value
 * could not tell a short value from a padded one.
 *
 * Assumptions: the element is narrowed rather than cast, so a query that returned something other than
 * an input fails here with a named reason instead of silently reading `undefined`.
 * @param {HTMLElement} control - One rendered input control.
 * @returns {string} Its value, exactly as the control holds it.
 * @throws {Error} If the element is not an input.
 */
function valueOfControl(control: HTMLElement): string {
  if (!(control instanceof HTMLInputElement)) {
    throw new Error('the element queried is not an input, so it carries no value');
  }
  return control.value;
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

  /*
   * WHY : Refactoring Rationale: the list is reached through the EMBOSSED NAME, where it was reached
   *       through the masked card rendering. The record's two identity rows are deleted -- the mapset
   *       declares one field per identifier and both are the criteria controls -- so the masked value is
   *       no longer inside the description list to climb from. The name is, and it is the first field of
   *       the record zone (`CRDNAME` at `app/bms/COCRDSL.bms` L107-L109).
   */
  const record = await screen.findByText(A_NAME_AT_THE_DECLARED_WIDTH);
  const list = record.closest('.ant-descriptions');
  expect(list).not.toBeNull();
  // WHY : Assumptions: the bordered variant is asserted through the class the design system emits for
  //       it, because a prop is not observable in a rendered tree by any other means. The variant is
  //       part of the target composition for a record view, and the border is what carries the cell
  //       separation the terminal got from the grid itself.
  expect(list?.classList.contains('ant-descriptions-bordered')).toBe(true);
  // WHY : ⚠️ Assumptions: three label cells, because the mapset's RECORD zone paints three value fields
  //       -- `CRDNAME` (L107), `CRDSTCD` (L116) and the `EXPMON`/`EXPYEAR` pair (L126 and L133)
  //       rendered as one expiry value. The two identifiers are painted by the criteria controls above
  //       the record zone, `ACCTSID` (L84) and `CARDSID` (L96), and a fourth cell here would be either
  //       a field the terminal did not paint or a second rendering of one it did.
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
  await waitForTheRecordView();

  /*
   * WHY : ⚠️ Refactoring Rationale: both identifiers are read from the CRITERIA CONTROLS' values, where
   *       they were read as rendered text out of the record view. The record's two identity rows are
   *       deleted, because the mapset declares one field per identifier and both are these controls --
   *       `ACCTSID` at `app/bms/COCRDSL.bms` L84-L88 and `CARDSID` at L96-L100 -- and the program moves
   *       the retrieved keys INTO them (`app/cbl/COCRDSLC.cbl` L463 and L471). So the value is now in a
   *       control's `value` rather than in a node's text, and this case follows it there. What it
   *       asserts is unchanged, and the property it guards is if anything better exposed: a control's
   *       value is a string or it is nothing, so a numeric coercion upstream shows up here as a lost
   *       leading zero exactly as it did before.
   */
  const { account, card } = searchFields();
  // WHY : Assumptions: the rendered value is byte-identical to the stored one, leading zeros
  //       included, because `app/cpy/CVCRD01Y.cpy` declares the identifier as `PIC X(11)` at L34 and
  //       redefines it numerically at L36 -- characters on the wire, a number only inside arithmetic.
  expect(account).toHaveValue(AN_ACCOUNT_NUMBER);
  expect(valueOfControl(account)).toHaveLength(DECLARED_WIDTHS.accountSearch);

  expect(card).toHaveValue(A_MASKED_RENDERING);
  expect(valueOfControl(card)).toHaveLength(DECLARED_WIDTHS.cardSearch);
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
 * The row-20 information line is painted ONCE, in the frame's pinned zone and never in the body.
 *
 * ⚠️ Purpose: hold the PLACEMENT of that line, not merely its text. The frame pins the zone carrying
 * rows 22, 23 and 24 with `position: sticky` and `inset-block-end: 0` (`ui/src/layout/AppShell.tsx`
 * L1324-L1332), so a band this screen composed inside its own scrolling body would be painted
 * UNDERNEATH that zone: on the sibling card-update screen a browser measured exactly that -- the band
 * at rect 775.67-815.67 inside `main` against a pinned zone spanning 764-860, with
 * `document.elementFromPoint(459, 796)` returning an element the band did not contain -- so the
 * sentence was unreadable until the operator scrolled. The reference cannot express that state at all:
 * `INFOMSG` is at `POS=(20,25)` and `ERRMSG` at `POS=(23,1)` on a 24-row display that does not scroll
 * (`app/bms/COCRDSL.bms` L139-L148).
 *
 * ⚠️ Assumptions: three things are asserted together and each one fails a different regression. Exactly
 * ONE band, because the mapset declares one such field and a screen that kept composing its own
 * alongside the published one would paint two. Containment by the pinned zone, because that is what
 * puts the line above the fold. And `closest('main')` being null, because that is what proves the
 * screen is not painting a second copy inside the scrolling region -- containment alone would still
 * pass if a duplicate existed elsewhere, and the count alone would still pass if the single band were
 * the one in the body.
 *
 * Assumptions: geometry is NOT asserted, because jsdom computes no layout and answers every rectangle
 * as zero. What is asserted is the structure that makes the frame's own pinning reach this line.
 * @returns {Promise<void>} Resolves once the band's placement has been established.
 */
async function paintsTheInformationLineInTheFramesPinnedZone(): Promise<void> {
  await renderAtACardAddress();

  const bands = screen.getAllByTestId(INFORMATION_BAND_TEST_ID);
  expect(bands, 'row 20 is one field, so one band may paint it').toHaveLength(1);
  const [band] = bands;
  const zone = screen.getByTestId(SHELL_PINNED_ZONE_TEST_ID);
  expect(zone).toContainElement(band ?? null);
  expect(
    band?.closest('main'),
    'a row-22 band inside the scrolling body is occluded by the pinned zone above it',
  ).toBeNull();
}

/**
 * The shade a control's value must NOT be painted in when the value is a real record field.
 *
 * ⚠️ Assumptions: this token name is written here rather than imported, because
 * `ui/src/theme/tokens.ts` publishes no constant for it -- and that absence is the point. Nothing in
 * this application paints anything in the disabled text shade DELIBERATELY; it is the design system's
 * own default for a control that refuses input, and it arrived on these two fields as a side effect of
 * marking them protected. A name is not a design value, so writing it costs the file no literal, and
 * naming it is what lets a case assert the shade is gone rather than merely that some other shade is
 * present.
 */
const DISABLED_TEXT_TOKEN = 'colorTextDisabled';

/**
 * Converts a design-system token name to the hyphenated fragment its custom property carries.
 *
 * ⚠️ Assumptions: a DIGIT run is separated from the letters before it as well as each word boundary,
 * because the design system hyphenates both -- a palette token named `red7` reaches the DOM as
 * `--ant-red-7`. No token this file names carries a digit today, and the boundary is kept anyway so a
 * later caller cannot get a false negative from the omission.
 *
 * Alternatives Considered: importing this from the sibling suite that already derives it,
 * `ui/src/test/accountView.test.tsx`. Rejected because importing a module that registers cases would
 * run that suite inside this file's worker, so the two-line derivation is repeated instead.
 * @param {string} tokenName - The token name as `ui/src/theme/tokens.ts` publishes it.
 * @returns {string} The kebab-cased fragment the custom property carries.
 */
function customPropertyFragmentFor(tokenName: string): string {
  return tokenName
    .replace(/([a-z0-9])([A-Z])/gu, '$1-$2')
    .replace(/([a-zA-Z])([0-9])/gu, '$1-$2')
    .toLowerCase();
}

/**
 * Converts a design-system token name to the whole custom-property reference it resolves to.
 *
 * Assumptions: the COMPLETE reference is built, parenthesis included, because one token name is a
 * prefix of another -- `colorText` against `colorTextDisabled` and `colorTextSecondary` -- so a
 * containment check on the shorter fragment would pass against an element painted in a longer one.
 * That is exactly the pair this file has to tell apart.
 * @param {string} tokenName - The token name as `ui/src/theme/tokens.ts` publishes it.
 * @returns {string} The `var(...)` reference the design system emits for that token.
 */
function customPropertyReferenceFor(tokenName: string): string {
  return `var(--ant-${customPropertyFragmentFor(tokenName)})`;
}

/**
 * A protected search field paints its value at the same intensity the editable arrival does.
 *
 * Purpose
 * -------
 * The regression guard for a measured defect: on the addressed arrival both search fields render
 * their value in the design system's DISABLED text shade, which a browser measured at
 * `rgba(0,0,0,0.25)` over the `rgba(0,0,0,0.04)` disabled surface -- so the account number and the
 * masked card rendering, which are real record values, read exactly like placeholder text.
 *
 * ⚠️ Assumptions: the expected colour is the reference's OWN and is asserted on both arrivals, which
 * is what makes this case about fidelity rather than about legibility. `1300-SETUP-SCREEN-ATTRS`
 * protects the two fields when the caller was the browse -- `MOVE DFHBMPRF TO ACCTSIDA / CARDSIDA` at
 * `app/cbl/COCRDSLC.cbl` L507-L508 -- and its "SETUP COLOR" block moves `DFHDFCOL`, the default
 * colour, into those same two fields under the identical condition at L526-L531, on a map that
 * already declares them `COLOR=DEFAULT` (`app/bms/COCRDSL.bms` L84-L88 and L96-L100). The program is
 * therefore asserting full intensity for the protected case specifically: one colour, two attribute
 * bytes. Asserting the two arrivals AGREE is the form that claim takes here.
 *
 * ⚠️ Assumptions: the disabled shade is asserted ABSENT by name as well, because the two tokens both
 * resolve to a neutral and an equality assertion alone would not say which one was expected -- and
 * the whole defect was that the wrong one of the pair was in effect.
 *
 * Assumptions: the computed value is read rather than the inline attribute, so the assertion covers
 * the cascade rather than one authoring mechanism: jsdom resolves these rules, and the disabled rule
 * the design system emits is `.ant-input-outlined.ant-input-disabled{color:var(--ant-color-text-disabled)}`.
 * A future fix that reached the same outcome through the theme instead would still pass.
 *
 * Assumptions: the fields are asserted still to REFUSE input on the addressed arrival, so this case
 * cannot be satisfied by making them editable. That protection is the other half of L507-L508 and is
 * what stops the record view offering a second way to change the record it displays.
 * @returns {Promise<void>} Resolves once both arrivals have been measured.
 */
async function paintsAProtectedFieldAtTheIntensityTheReferenceGivesIt(): Promise<void> {
  const authoritative = customPropertyReferenceFor(BMS_TEXT_COLOR_TOKENS.DEFAULT);
  const placeholderShade = customPropertyReferenceFor(DISABLED_TEXT_TOKEN);
  expect(authoritative).not.toBe(placeholderShade);

  const addressed = await renderTheRecordUnframed();
  const protectedFields = searchFields();
  for (const field of [protectedFields.account, protectedFields.card]) {
    /*
     * WHY : ⚠️ Refactoring Rationale: the refusal is asserted as READ-ONLY AND ENABLED, where it was
     *       asserted as disabled. `DFHBMPRF` is PROTECT with the modified-data tag set, not `DFHBMASK`
     *       -- a protected 3270 field is readable and cursor-addressable and refuses only typing, and
     *       the program positions the cursor into one of these two fields on this arrival (the
     *       `WHEN OTHER` arm at `app/cbl/COCRDSLC.cbl` L520-L523). `disabled` took the control out of
     *       the focus order and out of the accessibility tree, which is also what attracted the
     *       placeholder shade this case exists to keep away: the design system emits
     *       `.ant-input-outlined.ant-input-disabled{color:var(--ant-color-text-disabled)}`, so the
     *       mapping change removes the defect's mechanism rather than painting over it.
     */
    expect(field).toHaveAttribute('readonly');
    expect(field).toBeEnabled();
    expect(globalThis.getComputedStyle(field).color).toBe(authoritative);
    expect(globalThis.getComputedStyle(field).color).not.toBe(placeholderShade);
  }
  // WHY : Assumptions: the values are asserted present, because a field painted at full intensity
  //       while holding nothing would satisfy a colour assertion and still show the operator no
  //       record. `1200-SETUP-SCREEN-VARS` moves the two search keys into these fields' output
  //       subfields at `app/cbl/COCRDSLC.cbl` L457-L472, so both carry a value on this arrival.
  expect(protectedFields.account).toHaveValue(AN_ACCOUNT_NUMBER);
  expect(protectedFields.card).toHaveValue(A_MASKED_RENDERING);

  addressed.unmount();
  await renderTheSearchArrivalUnframed();
  const editableFields = searchFields();
  for (const field of [editableFields.account, editableFields.card]) {
    expect(field).toBeEnabled();
    // WHY : Assumptions: the editable arrival is asserted NOT read-only, which is the other half of the
    //       attribute-byte claim -- `1300-SETUP-SCREEN-ATTRS` moves `DFHBMFSE` into both fields when
    //       the caller was not the browse program (`app/cbl/COCRDSLC.cbl` L510-L511), and `DFHBMFSE`
    //       is UNPROTECT. Without this the read-only assertion above would pass against a screen that
    //       protected the fields on every arrival.
    expect(field).not.toHaveAttribute('readonly');
    expect(globalThis.getComputedStyle(field).color).toBe(authoritative);
  }
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
  it(
    "paints the information line in the frame's pinned zone",
    paintsTheInformationLineInTheFramesPinnedZone,
  );
  it(
    'paints a protected field at the intensity the reference gives it',
    paintsAProtectedFieldAtTheIntensityTheReferenceGivesIt,
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
  /*
   * WHY : ⚠️ Assumptions: this case takes the FRAMED arrangement, and it did not have to before. The
   *       row-20 line is now published through the frame's row-22 channel rather than composed in this
   *       screen's body -- a band inside the scrolling region is painted underneath the frame's sticky
   *       pinned zone, which was measured occluding the sibling save screen's acknowledgement outright
   *       -- so the frame is the only thing that renders it and an unframed tree has nothing to assert
   *       against. The sentence, its field and its provenance are unchanged.
   */
  await renderTheSearchArrival();

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
  /*
   * WHY : Assumptions: framed, for the reason recorded on the prompt case above -- the row-20 line is
   *       delegated to the frame's row-22 channel, so only a framed tree paints it.
   */
  await renderAtACardAddress();

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
 * The information line takes the appearance its own BMS field declares, not the turquoise role.
 *
 * Purpose
 * -------
 * The regression guard for a token-bridge substitution. `INFOMSG` is declared
 * `ATTRB=(PROT) COLOR=NEUTRAL` at `app/bms/COCRDSL.bms` L139-L143, and the screen passed
 * `severity="info"` for it -- which `ui/src/layout/MessageBand.tsx` L374-L375 resolves to
 * `BMS_TEXT_COLOR_TOKENS.TURQUOISE` rather than to `BMS_TEXT_COLOR_TOKENS.NEUTRAL`. Two distinct
 * source colour roles were therefore rendered as one, which is the substitution the bridge's own G3
 * note exists to prevent.
 *
 * ⚠️ Assumptions: both tokens are named and the wrong one is asserted ABSENT, because the two are
 * neutrals a rendered-colour equality assertion alone would not distinguish by intent -- and the
 * defect was that the wrong member of the pair was in effect. The names come from the bridge itself
 * rather than being written here, so this case cannot disagree with the map the band reads.
 *
 * Assumptions: the polite live-region role is asserted alongside the colour, because this line is
 * standing guidance rather than an outcome -- `1400-SEND-SCREEN` moves a value into it on every sent
 * map (`app/cbl/COCRDSLC.cbl` L496) -- so a screen reader must not be interrupted by it on every
 * turn. The design system publishes no neutral alert variant, so the informational CHROME is expected
 * to be unchanged and only the text token moves; asserting the role as well is what keeps this case
 * from passing on a band that had been promoted to the assertive row-23 treatment.
 * @returns {Promise<void>} Resolves once the band's colour role and politeness have been asserted.
 */
async function paintsTheInformationLineInTheNeutralRole(): Promise<void> {
  /*
   * WHY : Assumptions: framed, for the reason recorded on the prompt case above -- the row-20 line is
   *       delegated to the frame's row-22 channel, so only a framed tree paints it.
   */
  await renderAtACardAddress();

  const band = await screen.findByTestId(INFORMATION_BAND_TEST_ID);
  const sentence = within(band).getByText(MESSAGES.FOUND_CARDS_FOR_ACCOUNT.text.trim());
  expect(sentence.style.color).toBe(customPropertyReferenceFor(BMS_TEXT_COLOR_TOKENS.NEUTRAL));
  expect(sentence.style.color).not.toBe(
    customPropertyReferenceFor(BMS_TEXT_COLOR_TOKENS.TURQUOISE),
  );
  expect(within(band).getByRole('status')).toBeInTheDocument();
  expect(within(band).queryByRole('alert')).toBeNull();
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
  it('paints the information line in the neutral role', paintsTheInformationLineInTheNeutralRole);
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
 * Neither key on this record-view screen carries the acting emphasis.
 *
 * ⚠️ Refactoring Rationale: Enter is expected ORDINARY, and it was expected primary. The emphasis used
 * to be derived from the attention identifier, through `PRIMARY_ACTION_AIDS`, and that table cannot be
 * right across this application: the same identifier carries a browse on one mapset and a save or a
 * delete on another, so one AID-keyed answer paints them identically. `ui/src/layout/PfKeyBar.tsx` now
 * resolves emphasis from what a key's LABEL says its action does, and this screen declares both of its
 * keys `read-only` -- which is what its labels say. This is the record-VIEW program: `ENTER=Search
 * Cards` reads (`app/cbl/COCRDSLC.cbl` L336-L345 and L357-L371) and `F3=Exit` transfers control back
 * (L305-L333), and no arm anywhere in the program performs a `WRITE`, a `REWRITE` or a `DELETE`. So the
 * acting emphasis belongs on neither key, and its previous appearance on Enter was the AID table
 * asserting an action this screen does not have.
 *
 * ⚠️ Assumptions: the AID table is asserted to still CONTAIN `ENTER` while this screen's Enter renders
 * ordinary, and that disagreement is the point. The table remains the fallback for the screens that have
 * yet to declare their risks, so a case that merely asserted the ordinary variant would pass equally
 * against a screen whose declaration had been dropped and whose fallback happened to agree. Asserting
 * the two disagree proves the declaration is the operative input.
 * @returns {Promise<void>} Resolves once both variants have been asserted.
 */
async function marksEnterPrimaryAndTheExitKeyOrdinary(): Promise<void> {
  await renderAtACardAddress();

  expect(PRIMARY_ACTION_AIDS).toContain('ENTER');
  expect(PRIMARY_ACTION_AIDS).not.toContain('PFK03');
  // WHY : Assumptions: the variant is read off the class the design system emits for its primary
  //       button, because a prop is not otherwise observable in a rendered tree. No colour, radius or
  //       spacing value is asserted anywhere here -- those resolve through the theme in
  //       `ui/src/theme/tokens.ts`, and a literal would be exactly the hardcoded design value the
  //       token bridge exists to prevent.
  expect(legendControlFor('Enter').classList.contains('ant-btn-primary')).toBe(false);
  expect(legendControlFor('F3').classList.contains('ant-btn-primary')).toBe(false);
  // WHY : Assumptions: both are asserted to carry the DEFAULT treatment and not merely to lack the
  //       primary one. The design system emits one class per resolved button type, so a control that
  //       had lost its type altogether would satisfy a bare negative while rendering as neither of the
  //       two treatments the target design assigns.
  expect(legendControlFor('Enter').classList.contains('ant-btn-default')).toBe(true);
  expect(legendControlFor('F3').classList.contains('ant-btn-default')).toBe(true);
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
  await waitForTheRecordView();

  await takeTheTurn(first, 'PFK03');
  await waitForTheRecordToLeave();
  first.unmount();

  const second = await renderAtACardAddress();
  await waitForTheRecordView();

  await takeTheTurnFromTheLegend(second, 'F3');
  await waitForTheRecordToLeave();
}

/**
 * Waits until the record view is no longer mounted.
 *
 * Refactoring Rationale: the departure is observed through the EMBOSSED NAME for the same reason the
 * arrival is -- the masked card rendering is no longer text the record view paints, so its absence would
 * be satisfied by a screen that never rendered a record at all. See {@link waitForTheRecordView}.
 * @returns {Promise<void>} Resolves once the record view has gone from the document.
 */
async function waitForTheRecordToLeave(): Promise<void> {
  await waitFor(
    /**
     * Asserts the record view has left the document.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    (): void => {
      expect(screen.queryByText(A_NAME_AT_THE_DECLARED_WIDTH)).toBeNull();
    },
  );
}

/**
 * An unbound key is coerced into the Enter arm and reports nothing of its own.
 * @returns {Promise<void>} Resolves once the coercion and the silence have been asserted.
 */
async function coercesAnUnboundKeyIntoTheEnterArmSilently(): Promise<void> {
  const rendered = await renderAtACardAddress();
  await waitForTheRecordView();
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
 * Builds a read whose settlement this case controls.
 *
 * Assumptions: a hand-rolled deferred rather than a timer, because the property under test is the state
 * of the screen WHILE a read is outstanding, and a timer would make the window a duration to race
 * against instead of a state to observe.
 * @returns {{ promise: Promise<CardDetail>; settle: (card: CardDetail) => void }} The promise to hand
 *   the mocked client, and the function that settles it.
 */
function deferredRead(): { promise: Promise<CardDetail>; settle: (card: CardDetail) => void } {
  /*
   * WHY : Assumptions: the captured resolver is held as possibly-undefined rather than seeded with a
   *       throwing placeholder, because a placeholder is itself a function and every function in this
   *       file carries a doc comment -- so seeding it would document a branch the executor below makes
   *       unreachable. The guard in the returned settler states the same condition once.
   */
  let resolveRead: ((card: CardDetail) => void) | undefined;
  const promise = new Promise<CardDetail>(
    /**
     * Captures the resolver so the case can settle the read when it chooses.
     * @param {(card: CardDetail) => void} resolve - The promise's own resolver.
     * @returns {void} Nothing; the resolver is captured as a side effect.
     */
    (resolve: (card: CardDetail) => void): void => {
      resolveRead = resolve;
    },
  );
  return {
    promise,
    /**
     * Settles the held read with one record.
     * @param {CardDetail} card - The record the read answers with.
     * @returns {void} Nothing; the settlement is the effect.
     * @throws {Error} If called before the promise executor has run, which cannot happen for a native
     *   promise but is stated rather than assumed.
     */
    settle: (card: CardDetail): void => {
      if (resolveRead === undefined) {
        throw new Error('the deferred read was settled before it was armed');
      }
      resolveRead(card);
    },
  };
}

/**
 * The outstanding read is announced through a live region that is mounted and empty when idle.
 *
 * ⚠️ Assumptions: the region's presence and EMPTINESS are asserted BEFORE the read is started, and that
 * ordering is the point. `ui/src/layout/fieldHelp.tsx` records that a live region has to be in the
 * accessibility tree before its content changes for the change to be announced, so a region that mounts
 * with its sentence already in place is frequently treated as initial content and read by nothing --
 * which loses the transition that matters. A case that only looked for the sentence would pass against
 * exactly that arrangement.
 *
 * ⚠️ Assumptions: the SAME DOM node is asserted across all three states, by identity. The screen replaces
 * its whole body with a spinner while a read is outstanding, so the only thing keeping this region alive
 * across that swap is that both arms return the same root element type with the region as child 0 -- and
 * a node identity comparison is what proves React updated it rather than remounting it. A remounted
 * region carries the sentence as initial content and announces nothing, which is indistinguishable from
 * the fix by text alone.
 *
 * Assumptions: the sentence is the catalogue's authored `REQUEST_IN_PROGRESS` compared by identity, not
 * a literal, so a reword in the catalogue moves this case with it rather than breaking it.
 * @returns {Promise<void>} Resolves once all three states have been observed.
 */
async function announcesTheOutstandingRead(): Promise<void> {
  const rendered = await renderTheSearchArrival();
  const idle = screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID);
  expect(idle).toHaveTextContent('');

  const held = deferredRead();
  vi.mocked(lookupCard).mockReturnValue(held.promise);
  await fillTheSearchFields(rendered, AN_ACCOUNT_NUMBER, A_CARD_NUMBER, 'account');
  await takeTheTurn(rendered, 'ENTER');

  const busy = screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID);
  expect(busy).toBe(idle);
  expect(busy).toHaveTextContent(REQUEST_IN_PROGRESS);

  await act(
    /**
     * Lets the held read settle inside the scope, so the screen's own update is flushed.
     * @returns {Promise<void>} Resolves once the read has settled.
     */
    async (): Promise<void> => {
      held.settle(aCard());
      await held.promise;
    },
  );

  await waitFor(
    /**
     * Waits until the announcement has been withdrawn.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      const settled = screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID);
      expect(settled).toBe(idle);
      expect(settled).toHaveTextContent('');
    },
  );
}

/**
 * The search key is declined while its own read is outstanding, and the exit key stays live.
 *
 * ⚠️ Assumptions: the decline is asserted PER KEY -- the search key stops starting reads while the exit
 * key keeps its binding, its enabled state and its accessible name. Withdrawing every key would strand
 * an operator on a read that never answers, and the reference never withdraws a key at all: a 3270
 * terminal inhibits input for the duration of a turn and releases it when the screen answers.
 *
 * ⚠️ Assumptions: the declined key's own control is asserted to stay PRESENT, ENABLED and NAMED, not to
 * disappear or to go disabled. A disabled control leaves the accessibility tree's focus order, so an
 * operator tabbing through the legend would find the set of controls changing under them mid-turn; the
 * contract is that a busy key is a valid key pressed early, so it stays exactly where it was.
 *
 * ⚠️ Assumptions: no invalid-key sentence appears, and that absence is asserted rather than assumed. The
 * decline is SILENT: an exhaustive search of `app/cbl/COCRDSLC.cbl` finds no `CCDA-MSG-INVALID-KEY` move
 * anywhere in the program, so a message here would be a fabrication -- and a busy key is not an invalid
 * one in any case.
 * @returns {Promise<void>} Resolves once the decline and the surviving key have been observed.
 */
async function declinesTheSearchKeyWhileItsOwnReadIsOutstanding(): Promise<void> {
  const held = deferredRead();
  vi.mocked(lookupCard).mockReturnValue(held.promise);
  const rendered = await renderTheSearchArrival();

  await fillTheSearchFields(rendered, AN_ACCOUNT_NUMBER, A_CARD_NUMBER, 'account');
  await takeTheTurn(rendered, 'ENTER');
  expect(vi.mocked(lookupCard)).toHaveBeenCalledTimes(1);

  await takeTheTurn(rendered, 'ENTER');
  await takeTheTurnFromTheLegend(rendered, 'Enter');
  // WHY : ⚠️ Assumptions: BOTH drivers are exercised against the outstanding read, because the legend
  //       control and the key press reach the same binding and a decline that held for only one of
  //       them would leave the other able to start a second read from a screen showing a spinner.
  expect(vi.mocked(lookupCard)).toHaveBeenCalledTimes(1);

  const declined = legendControlFor('Enter');
  expect(declined).toBeEnabled();
  expect(declined).toHaveAccessibleName();
  expect(legendControlFor('F3')).toBeEnabled();
  expect(legendControlFor('F3')).toHaveAccessibleName();
  expect(document.body.textContent).not.toContain(INVALID_KEY_PRESSED.trim());

  await act(
    /**
     * Settles the held read so the screen leaves its outstanding state inside an act scope.
     * @returns {Promise<void>} Resolves once the read has settled.
     */
    async (): Promise<void> => {
      held.settle(aCard());
      await held.promise;
    },
  );
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
  it('announces the outstanding read through a live region', announcesTheOutstandingRead);
  it(
    'declines the search key while its own read is outstanding',
    declinesTheSearchKeyWhileItsOwnReadIsOutstanding,
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
  await waitForTheRecordView();

  /*
   * WHY : Refactoring Rationale: the rendering is read from the CARD CONTROL's value, where it was read
   *       as text out of the record view. The record's card-number row is deleted -- the mapset declares
   *       one field for that identifier and it is this control, `CARDSID` at `app/bms/COCRDSL.bms`
   *       L96-L100, which the program moves the retrieved key into at `app/cbl/COCRDSLC.cbl` L471 -- so
   *       the masked value now lives in the control. The posture being asserted is unchanged.
   */
  // WHY : Assumptions: the pattern is imported from `ui/src/api/masking.ts` rather than written here,
  //       so this asserts the form the contract itself requires -- twelve mask characters then the last
  //       four digits of a `PIC X(16)` number (`app/cpy/CVACT02Y.cpy` L5).
  expect(MASKED_CARD_NUMBER.test(valueOfControl(searchFields().card))).toBe(true);
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
  await waitForTheRecordView();

  // WHY : Refactoring Rationale: read from the card control's value, for the reason recorded on the
  //       ordinary-operator case above -- the record's card-number row is deleted as a duplicate of
  //       this control.
  expect(MASKED_CARD_NUMBER.test(valueOfControl(searchFields().card))).toBe(true);
  // WHY : Assumptions: the administrative read is asserted NOT to have been called, which is the
  //       observable form of "this screen does not reach the exception". Asserting only the rendered
  //       value would pass equally if the screen had fetched a whole number and then masked it itself
  //       -- and re-masking a value client-side is the failure mode the screen's own note rejects,
  //       because it would hide a server-side rendering fault the client is positioned to report.
  expect(vi.mocked(getAdminCardDetail)).not.toHaveBeenCalled();
  expectNoWholeCardNumberAnywhere();
}

/**
 * Asserts that nothing rendered carries a whole sixteen-digit card number.
 *
 * Assumptions: the check is a run of sixteen digits rather than one fixture value, so it catches an
 * unmasked number the screen composed as well as one it was handed.
 *
 * ⚠️ Refactoring Rationale: every INPUT VALUE is searched as well as the document text, and only the
 * text was searched before. The two identity values moved out of the record view and into the criteria
 * controls -- the mapset declares one field per identifier and both are those controls -- and an input's
 * value is not part of any node's `textContent`, so a text-only scan would now pass over the very
 * element that holds the card number. Following the value is what keeps this guard as strong as it was;
 * leaving it would have silently retired the strongest assertion in this file.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function expectNoWholeCardNumberAnywhere(): void {
  const wholeNumber = /[0-9]{16}/u;
  expect(wholeNumber.test(document.body.textContent ?? '')).toBe(false);
  for (const control of Array.from(document.querySelectorAll('input'))) {
    expect(wholeNumber.test(control.value), 'an input value carries a whole card number').toBe(
      false,
    );
  }
}

/**
 * The screen exposes exactly the five fields the mapset paints, and nothing further.
 *
 * ⚠️ Assumptions: the three-digit verification value the card record declares at
 * `app/cpy/CVACT02Y.cpy` L7 -- between the account identifier and the embossed name -- is returned by
 * NO operation of the card contract and declared by no shape in `ui/src/api/types.ts`, so no fixture in
 * this file can carry one and no rendering can disclose one. The prohibition is absolute and has no
 * administrative exception anywhere in the application, which is what distinguishes it from the masking
 * rule above: masking has one permitted exception, this has none. The assertion is therefore the
 * completeness of the screen -- five values in total, being the five the mapset paints -- so a sixth
 * field appearing anywhere fails this case whatever it holds.
 *
 * ⚠️ Refactoring Rationale: the five are counted across TWO surfaces now, and they were counted as five
 * cells of one description list. The mapset itself puts them on two: `ACCTSID` at `POS=(7,45)`
 * (`app/bms/COCRDSL.bms` L84-L88) and `CARDSID` at `POS=(8,45)` (L96-L100) are criteria fields the
 * program moves the retrieved keys into (`app/cbl/COCRDSLC.cbl` L463 and L471), and only `CRDNAME`
 * (L107-L109), `CRDSTCD` (L116-L119) and the `EXPMON`/`EXPYEAR` pair (L126-L136) sit in the record zone.
 * The screen used to render the two identifiers in BOTH places -- a browser measured each one twice, in
 * two typefaces -- so counting five cells in the record view was counting the duplication. Counting the
 * two surfaces separately is what makes a return of that duplication fail here.
 * @returns {Promise<void>} Resolves once the screen's completeness has been asserted.
 */
async function exposesOnlyTheFiveFieldsTheMapsetPaints(): Promise<void> {
  await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });

  await renderTheRecordUnframed();
  await waitForTheRecordView();

  expect(recordLabelCells()).toHaveLength(RECORD_LABEL_COUNT);
  // WHY : Assumptions: the label of every cell is one of the three the mapset paints in its record
  //       zone, read from `app/bms/COCRDSL.bms` L106, L115 and L125. Counting alone would pass if a
  //       cell had been swapped for a field the terminal never showed, so the identities are checked
  //       too -- and the two identifier labels are asserted ABSENT from this surface, which is what
  //       fails if either row returns.
  const labels = recordLabelCells().map(textOfElement);
  for (const label of labels) {
    expect(THE_THREE_PAINTED_RECORD_LABELS.some(matchesCollapsed(label))).toBe(true);
  }
  for (const identifierLabel of THE_TWO_PAINTED_CRITERIA_LABELS) {
    expect(labels.some(matchesCollapsed(identifierLabel))).toBe(false);
  }
  // WHY : Assumptions: the two identifiers are asserted present as CONTROLS, so this case cannot be
  //       satisfied by a screen that dropped them rather than by one that paints each of the five
  //       exactly once. `searchFields` asserts the count of two on the way through.
  const { account, card } = searchFields();
  expect(account).toHaveValue(AN_ACCOUNT_NUMBER);
  expect(card).toHaveValue(A_MASKED_RENDERING);
}

/**
 * The three value labels the mapset paints in its RECORD zone, in the order it paints them.
 *
 * Assumptions: these are `INITIAL=` literals of the mapset itself, so they live beside the screen that
 * paints them rather than in the message catalog -- the catalog's own boundary excludes a field label,
 * which is positional and meaningless apart from the control beside it. They are compared with interior
 * whitespace collapsed, because the mapset pads each one to a fixed cell width so its colons line up
 * down the column and a browser collapses that padding when it renders.
 *
 * ⚠️ Refactoring Rationale: the two identifier labels are in {@link THE_TWO_PAINTED_CRITERIA_LABELS}
 * instead, because the mapset paints them over the CRITERIA fields at rows 7 and 8 and not over the
 * record zone at rows 11 to 15. Holding all five in one list is what let the record view carry a second
 * rendering of each identifier without any case objecting.
 */
const THE_THREE_PAINTED_RECORD_LABELS = [
  'Name on card      :',
  'Card Active Y/N   : ',
  'Expiry Date       : ',
] as const;

/**
 * The two value labels the mapset paints over its CRITERIA fields, in the order it paints them.
 *
 * Assumptions: `INITIAL=` literals again, at `POS=(7,23)` (`app/bms/COCRDSL.bms` L79-L83) and
 * `POS=(8,23)` (L91-L95). They name the two controls the mapset declares `UNPROT`, which are the one
 * place each identifier is painted.
 */
const THE_TWO_PAINTED_CRITERIA_LABELS = ['Account Number    :', 'Card Number       :'] as const;

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
  await waitForTheRecordView();

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
  await waitForTheRecordView();

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
 * Returns the static alias the router publishes for one reference program.
 *
 * Assumptions: the address is READ from the router's own alias table rather than written as a literal,
 * so this file tracks the address the application publishes instead of a copy of it. A withdrawn alias
 * then fails here, with a sentence naming the program, rather than in a route that silently cannot
 * match.
 * @param {string} program - Name of the reference program, as the alias table records it.
 * @returns {string} That program's parameter-free address.
 * @throws {RangeError} If the alias table holds no entry for the program.
 */
function keylessEntryAddressFor(program: string): string {
  for (const entry of KEYLESS_ENTRY_ROUTES) {
    if (entry.program === program) {
      return entry.path;
    }
  }
  throw new RangeError(`no keyless entry route is published for ${program}`);
}

/** The static address this screen is also reachable at, which declares NO route parameter. */
const KEYLESS_ENTRY_ADDRESS = keylessEntryAddressFor('COCRDSLC');

/**
 * An arrival on the STATIC alias keeps the whole frame, reads nothing, and prompts for the keys.
 *
 * ⚠️ Purpose: hold the arrival the router's alias table creates, which no other case here reaches. The
 * other selector-free cases mount this screen with no route pattern at all, so the parameter is absent
 * because nothing declared it; this case mounts it at the address the application actually publishes
 * (`ui/src/router.tsx` {@link KEYLESS_ENTRY_ROUTES}), where the pattern is STATIC and therefore supplies
 * `undefined` for `cardKey` on a route that matched. The distinction is the one that matters: a screen
 * that treated an undefined parameter as a broken link would answer a working menu option with a refusal.
 *
 * ⚠️ Assumptions: the alias is asserted to carry no pattern segment, because a sentinel-bearing alias --
 * `/cards/-/view`, say -- would satisfy every other assertion here while handing the screen a parameter
 * it must then decide is not a selector. `ui/src/router.tsx` records why the alias is static instead:
 * the eleven main-menu options at `app/cpy/COMEN02Y.cpy` name programs and carry no record, so this
 * program's own first turn needs an address of its own, and the reference paints exactly this map when
 * its selection carrier arrives blank (`app/cbl/COCRDSLC.cbl` L350-L356, both fields unprotected at
 * L510-L511).
 *
 * ⚠️ Assumptions: NO read of any kind is issued, asserted across all three of this screen's client
 * calls rather than inferred from the absence of a record on the glass. There is no key to read by, so
 * a request here would be one built from `undefined` -- which reaches a service as a literal
 * `/cards/undefined` and is answered with a status the operator can do nothing about.
 *
 * Assumptions: the whole frame is counted, not merely the absence of an error page. A regression of this
 * class replaces the frame with a centred result, so asserting only that the prompt appears would pass
 * against a frame that had lost its header, its legend or its pinned zone.
 * @returns {Promise<void>} Resolves once the selector-free arrival has been observed.
 */
async function keepsTheFrameOnTheKeylessEntryRoute(): Promise<void> {
  await act(
    /**
     * Mounts the screen at its static alias, where the route declares no parameter at all.
     * @returns {Promise<void>} Resolves once the arrival has settled.
     */
    async (): Promise<void> => {
      await renderInAppShell(<CardDetailScreen />, {
        initialEntries: [KEYLESS_ENTRY_ADDRESS],
        routePath: KEYLESS_ENTRY_ADDRESS,
      });
    },
  );

  expect(KEYLESS_ENTRY_ADDRESS).not.toContain(':');
  expect(document.querySelector('.ant-result')).toBeNull();
  expect(screen.getAllByTestId(APP_SHELL_TEST_ID)).toHaveLength(1);
  expect(document.querySelectorAll('main')).toHaveLength(1);
  expect(screen.getAllByTestId(SHELL_PINNED_ZONE_TEST_ID)).toHaveLength(1);
  expect(screen.getByRole('heading', { name: CARD_DETAIL_TITLE })).toBeInTheDocument();
  expectVerbatimMessage('CCDL');
  expectVerbatimMessage('COCRDSLC');
  /*
   * WHY : Assumptions: the row-20 line carries the program's OWN prompt and not the invalid-link
   *       guidance, which is the assertion that separates this arrival from an unaddressable one. The
   *       screen selects the guidance only when a parameter WAS supplied and could not be opened, so
   *       seeing it here would mean the undefined parameter had been read as a bad one.
   */
  const information = await screen.findByTestId(INFORMATION_BAND_TEST_ID);
  expect(information.textContent ?? '').toContain(MESSAGES.WS_PROMPT_FOR_INPUT.text);
  expect(information.textContent ?? '').not.toContain(CARD_DETAIL_INVALID_LINK_GUIDANCE);
  expect(screen.getByTestId(MESSAGE_BAND_TEST_ID)).toBeInTheDocument();
  expect(screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).toBeInTheDocument();
  expect(vi.mocked(getCard)).not.toHaveBeenCalled();
  expect(vi.mocked(getAdminCardDetail)).not.toHaveBeenCalled();
  expect(vi.mocked(lookupCard)).not.toHaveBeenCalled();
}

/**
 * Registers the selection-context cases.
 * @returns {void} Nothing; registration is the effect.
 */
function selectionContextCases(): void {
  it('keeps the frame on the keyless entry route', keepsTheFrameOnTheKeylessEntryRoute);
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
