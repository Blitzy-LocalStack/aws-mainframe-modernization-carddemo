/**
 * @file Pins the cross-screen contracts of the persistent application shell -- the four zones that
 * replace the fixed 24x80 BMS frame -- so that the 21 per-screen suites do not each re-assert them.
 *
 * Purpose
 * -------
 * Three components carry every contract that is identical on all 21 screens:
 * `ui/src/layout/AppShell.tsx` composes the zones, `ui/src/layout/ScreenHeader.tsx` paints the title
 * band, and `ui/src/layout/MessageBand.tsx` paints the row-23 message line. What is asserted here is
 * the part of their behaviour that comes from the immutable baseline rather than from a component
 * decision: the two `PIC X(40)` title constants character for character, the 75-character message
 * content contract and its distinction from the 78- and 80-character display field, the `LOW-VALUES`
 * no-message state, the two sign-off strings that must never be merged, the `NOT_OK`/`BLANK`
 * field-error shape with its literal `'*'` marker, and the statelessness that replaced
 * `DFHCOMMAREA`.
 *
 * Module analogue of Parameters: the subjects are `AppShell`, `ScreenHeader` and `MessageBand`,
 * reached through the shared harness in `./setup` so each is surrounded by the same
 * `ConfigProvider` and router the application builds. The sibling `PfKeyBar` is asserted to be
 * MOUNTED and nothing more -- its labels and its click-versus-keypress equivalence belong to the
 * PF-key suite, and duplicating them here would create two owners for one contract.
 *
 * Module analogue of Returns: no value; each case registers with the runner and asserts against a
 * rendered tree or against the message catalog.
 *
 * Module analogue of Exceptions: a failure here is a SHELL regression, so it affects all 21 screens
 * at once rather than the one screen whose suite reported it. Read a failure as "the frame changed",
 * not "this screen changed".
 *
 * ⚠️ Assumptions: these cases are the ONLY verification the shell gets, and that is stated plainly
 * rather than glossed. `tests/README.md` section 1.1 records that the online `CO*` CICS programs
 * "cannot run end-to-end without a CICS runtime (absent on the runner); only their extractable
 * field-validation logic is unit-tested". The existing COBOL suite is therefore the functional-parity
 * oracle for the BATCH chain only: there is no golden master for any screen and no COBOL test to
 * compare a rendered frame against. Every fidelity claim the shell makes is either pinned here or
 * pinned nowhere, which is why these cases assert against the catalogued baseline values instead of
 * against a snapshot of the shell's own output.
 *
 * Assumptions: the documentation obligation on this file comes from two documents that agree
 * completely, so following one satisfies the other. The project's Rule 1 (Explainability) requires a
 * docstring on every module entry point and function stating purpose, parameters, returns and
 * exceptions, plus an inline comment giving the WHY of each non-obvious decision from one of
 * Alternatives Considered, Refactoring Rationale, Assumptions or Trade-offs. `tests/README.md`
 * section 12 imposes the same obligation on "every new test, fixture builder, helper, mock, and
 * runner routine" in the same four categories and calls it "a hard review gate". This file therefore
 * extends an established house convention rather than introducing a competing one.
 *
 * Assumptions: no case here asserts geometry, a pixel offset, a character coordinate or a 24x80
 * grid, and the omission is deliberate. Design gap G1 surrenders absolute character positioning
 * because reproducing it in a browser would be hostile to accessibility and impossible to make
 * responsive; what G1 undertakes to preserve is field grouping, reading order and tab order, so those
 * are what the accessibility group below asserts. A future maintainer who "fixes" the missing layout
 * assertion by measuring a rendered offset would be testing against the architecture rather than for
 * it.
 */

/*
 * Assumptions: every test API is imported rather than taken from an ambient global, because
 * `ui/tsconfig.json` keeps its `types` list EMPTY -- so nothing is declared ambiently and a bare
 * `describe` fails to compile as `TS2593: Cannot find name 'describe'`. `npm run typecheck` runs
 * `tsc --noEmit` over this directory and `.github/workflows/ui-ci.yml` gates on it, so the import is
 * a compile requirement and not a style preference. The runner's own `globals` option is `true` in
 * `ui/vitest.config.ts` for a separate reason recorded beside it; the enforcing mechanism here is the
 * empty `types` list and never that option. Every landed suite in this package imports the same way.
 */
import { renderHook, screen, within } from '@testing-library/react';
import { Form, Input, theme } from 'antd';
import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';

import type { FieldValidationState } from '../api/types';
import { CARDDEMO_ADMIN_GROUP, useAuth } from '../hooks/useAuth';
import { SHELL_CONTENT_ELEMENT_ID, SKIP_TO_CONTENT_LABEL, useShellSlot } from '../layout/AppShell';
import {
  MESSAGE_BAND_CONTENT_WIDTH,
  MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH,
  MESSAGE_BAND_TEST_ID,
  MessageBand,
  isMessageBandEmpty,
} from '../layout/MessageBand';
import type { MessageBandSeverity } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import {
  HEADER_DATE_FORMAT,
  HEADER_PROMPT_LABELS,
  HEADER_TIME_FORMAT,
  RETIRED_HEADER_FIELDS,
} from '../layout/ScreenHeader';
import type { PfKeyBinding } from '../layout/usePfKeys';
import {
  ABEND_DATA_FIELDS,
  APP_ORGANISATION_TITLE_DISPLAY,
  APP_TITLE_DISPLAY,
  COMMON_MESSAGES,
  MESSAGE_BAND,
  SCREEN_TITLES,
  THANK_YOU_CARDDEMO,
  THANK_YOU_CCDA,
  padToDeclaredWidth,
} from '../messages/messages';
import { cardDemoTheme } from '../theme/antdTheme';
import { FIELD_ERROR_TOKENS } from '../theme/tokens';
import {
  apiError,
  expectVerbatimMessage,
  fieldError,
  renderInAppShell,
  renderWithProviders,
  seedSession,
  shellLandmark,
} from './setup';

/**
 * Text the probe screen paints so a case can prove it was reached through the outlet.
 *
 * Assumptions: the marker is a sentence no catalogued baseline string contains, so a query for it
 * can never accidentally match a shell-painted title or message.
 */
const OUTLET_MARKER = 'probe screen body reached through the outlet';

/**
 * The transaction identifier the probe publishes, at the width the symbolic maps declare.
 *
 * Assumptions: four characters, because `TRNNAMEI PIC X(4)` is declared identically on all 17 base
 * symbolic maps in `app/cpy-bms/`. A wider value would be a fixture that no screen can produce.
 */
const PROBE_TRANSACTION_ID = 'CT00';

/**
 * The program name the probe publishes, at the width the symbolic maps declare.
 *
 * Assumptions: eight characters, because `PGMNAMEI PIC X(8)` is declared identically on all 17 base
 * symbolic maps in `app/cpy-bms/`.
 */
const PROBE_PROGRAM_NAME = 'COPROBEC';

/**
 * A fixed instant, so a case that reads the painted date or time is not racing the clock.
 *
 * Assumptions: the value is passed to the shell as its `now` rather than being stubbed globally,
 * which is the same seam the baseline uses -- `app/jcl/INTCALC.jcl` injects the business date through
 * `PARM=` instead of reading the wall clock, and `tests/README.md` section 11 records injected dates
 * as one of the suite's determinism guarantees. The month, day, hour, minute and second are all
 * two-digit so no assertion depends on a single-digit rendering.
 */
const PAINTED_AT = new Date(2022, 6, 18, 13, 24, 35);

/**
 * A legend binding, so `PfKeyBar` renders a region instead of returning `null`.
 *
 * Assumptions: one binding is enough and no more are supplied. `PfKeyBar` returns `null` for an empty
 * binding list, so a case asserting the legend is MOUNTED needs at least one; asserting anything
 * about the binding's label or its activation belongs to the PF-key suite, which owns that contract.
 */
const PROBE_PF_KEYS: readonly PfKeyBinding[] = [
  { aid: 'PFK03', action: 'back', label: 'F3=Back', enabled: true },
];

/**
 * Ignores a delegated key activation.
 *
 * Assumptions: the shell requires an `onInvoke` to publish a legend at all, and no case here
 * activates a key, so this records the requirement without asserting on it. The forwarding contract
 * is owned by the PF-key suite.
 * @returns {void} Nothing; the activation is deliberately discarded.
 */
function ignoreKeyActivation(): void {
  return undefined;
}

/**
 * A screen that delegates all four zones to the shell and paints one marker of its own.
 *
 * Purpose
 * -------
 * `ScreenHeader` and `MessageBand` are rendered by the shell only when a screen has PUBLISHED a
 * corresponding slot, so a bare `<AppShell />` paints neither. Eighteen of the 21 screens delegate
 * through `useShellSlot`, and this probe reproduces that exact arrangement so the zones under test
 * are the ones the application actually builds.
 *
 * Alternatives Considered: passing `screen`, `message` and `pfKeys` to `AppShell` as props, which it
 * accepts. Rejected because the shared harness mounts the shell with no props -- deliberately, so the
 * child arrives through the outlet -- and because the prop path is the shell's own unit-test seam
 * while `useShellSlot` is the path every delegating screen takes. Testing the seam the application
 * does not use would leave a regression in the delegation store invisible.
 * @param {object} props - What the probe is to publish.
 * @param {string | null} props.message - Message text for the row-23 band, or `null` for a quiet
 *   screen that still reserves the row.
 * @param {MessageBandSeverity} props.severity - Severity the band is to announce the message at.
 * @returns {ReactElement} The probe's own body, painted inside the shell's content region.
 */
function ProbeScreen({
  message,
  severity,
}: {
  readonly message: string | null;
  readonly severity: MessageBandSeverity;
}): ReactElement {
  useShellSlot({
    screen: { transactionId: PROBE_TRANSACTION_ID, programName: PROBE_PROGRAM_NAME },
    message: { text: message, severity },
    pfKeys: { keys: PROBE_PF_KEYS, onInvoke: ignoreKeyActivation },
    now: PAINTED_AT,
  });

  return <p>{OUTLET_MARKER}</p>;
}

/**
 * Renders the shell with a delegating child mounted at the root, through the shared harness.
 *
 * Assumptions: the harness is used rather than a router assembled here. `ui/src/App.tsx` is the
 * application's only `ConfigProvider`, so a subject rendered bare resolves antd's default tokens
 * instead of the BMS bridge in `ui/src/theme/tokens.ts` -- which would make every tokenised assertion
 * below assert against a tree the application does not build. The harness supplies that provider and
 * a memory router, and mounts the shell as a pathless layout route so the child arrives through
 * `<Outlet />`.
 * @param {string | null} [message] - Message text for the band; omitted, the screen is quiet and the
 *   row is reserved empty.
 * @param {MessageBandSeverity} [severity] - Severity for that message; defaults to the band's own
 *   row-23 default.
 * @returns {Promise<void>} Resolves once the shell and its child are in the document.
 */
async function renderShell(
  message: string | null = null,
  severity: MessageBandSeverity = 'error',
): Promise<void> {
  await renderInAppShell(<ProbeScreen message={message} severity={severity} />, {
    initialEntries: ['/'],
    routePath: '/',
  });
}

/**
 * Names the BMS field a retired-slot or abend-field record describes.
 *
 * Assumptions: a named declaration rather than an inline arrow, which is the convention this package
 * settled on for every callback: the documentation gate requires a block on a function expression in
 * ANY position, and Prettier detaches a block comment that follows an argument comma, so an inline
 * arrow cannot carry its own block legibly.
 * @param {object} record - A record that names a BMS field.
 * @param {string} record.field - The BMS field name the record describes.
 * @returns {string} The field name.
 */
function fieldNameOf(record: { readonly field: string }): string {
  return record.field;
}

/**
 * Reports the `PIC X(n)` width a catalogued field record declares.
 * @param {object} record - A record that carries a declared width.
 * @param {number} record.declaredWidth - Width from the record's `PIC X(n)` clause.
 * @returns {number} The declared width.
 */
function declaredWidthOf(record: { readonly declaredWidth: number }): number {
  return record.declaredWidth;
}

/**
 * Reports whether a published member name looks like a setter.
 *
 * Assumptions: the test is on the NAME rather than on the value, because what must be absent is any
 * published way to write authority -- a member called `setGroups` would be a setter whether it were a
 * function or an assignable property.
 * @param {string} member - A member name published by the auth reading.
 * @returns {boolean} `true` when the name begins with `set`.
 */
function looksLikeASetter(member: string): boolean {
  return /^set/i.test(member);
}

/**
 * The row-23 band element, populated or reserved.
 *
 * Assumptions: the band is located by the test identifier the module publishes rather than by role,
 * because its role is `alert` or `status` according to SEVERITY and the reserved empty band is
 * `aria-hidden` with no role at all. `./setup`'s landmark locator deliberately excludes it for that
 * reason, so a case reaching for the band uses the published identifier.
 * @returns {HTMLElement} The band element.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Test identifier of the probe that reports which theme reached it through the shell.
 */
const RESOLVED_TOKEN_TEST_ID = 'probe-resolved-colour';

/**
 * A screen that reports the informational colour the theme in scope resolves to.
 *
 * Purpose
 * -------
 * A `ConfigProvider` renders no element of its own, so its presence cannot be detected in the DOM
 * directly. What it does change is the token a descendant resolves, which makes the token the
 * observable proxy: painted inside the shell, this probe reports whichever theme actually reached it.
 * @returns {ReactElement} An element whose text is the resolved `colorInfo` value.
 */
function ResolvedTokenProbe(): ReactElement {
  const { token } = theme.useToken();

  return <span data-testid={RESOLVED_TOKEN_TEST_ID}>{token.colorInfo}</span>;
}

/**
 * All four zones are painted when a screen delegates them.
 *
 * Assumptions: the three landmark zones are located by accessible ROLE -- antd's `Layout.Header`
 * emits `<header>`, `Layout.Content` emits `<main>` and `Layout.Footer` emits `<footer>`, so the
 * roles are `banner`, `main` and `contentinfo`. The role is the property a screen reader acts on,
 * whereas a test identifier would pass just as well on a `<div>` that announces nothing.
 *
 * Assumptions: the fourth zone is located by its published identifier instead, because the band sits
 * between the content and the footer as a SIBLING of the three landmarks rather than inside any of
 * them -- a screen-level outcome is not information about the document, which is what `contentinfo`
 * means.
 * @returns {Promise<void>} Resolves once every zone has been found.
 */
async function paintsAllFourZones(): Promise<void> {
  await renderShell('Account updated successfully', 'success');

  expect(shellLandmark('titleBand')).toBeInTheDocument();
  expect(shellLandmark('screenBody')).toBeInTheDocument();
  expect(messageBand()).toBeInTheDocument();
  expect(shellLandmark('keyLegend')).toBeInTheDocument();
}

/**
 * The child route's element is painted inside the content region, through the outlet.
 *
 * Assumptions: containment is asserted rather than mere presence. The shell renders `children ??
 * <Outlet />`, so a marker that existed ANYWHERE in the document would also be satisfied by a tree
 * in which the outlet had been lost and the child mounted as a sibling of the frame -- which is the
 * regression this case exists to catch.
 * @returns {Promise<void>} Resolves once the child's marker has been found inside the content region.
 */
async function paintsTheChildRouteThroughTheOutlet(): Promise<void> {
  await renderShell();

  expect(within(shellLandmark('screenBody')).getByText(OUTLET_MARKER)).toBeInTheDocument();
}

/**
 * The function-key legend is mounted inside the footer zone.
 *
 * Assumptions: this asserts MOUNTING and nothing else. The legend's labels, its
 * click-versus-keypress equivalence and its dispatch back to the publishing screen are owned by the
 * PF-key suite; asserting them here as well would give one contract two owners, so the next change to
 * it would have two places to be updated and one of them would be missed.
 * @returns {Promise<void>} Resolves once the legend region has been found in the footer.
 */
async function mountsTheKeyLegendInTheFooterZone(): Promise<void> {
  await renderShell();

  // Assumptions: the legend is a NAVIGATION landmark, not a generic region. `PfKeyBar` renders
  //   `component="nav"` with its label as `aria-label`, and its own documentation calls the result a
  //   navigation landmark -- so `navigation` is the role to query. Row 24 was identified to a
  //   terminal operator by its fixed position, which a landmark replaces with a name.
  const legend = within(shellLandmark('keyLegend')).getByRole('navigation', {
    name: PF_KEY_BAR_REGION_LABEL,
  });

  expect(legend).toBeInTheDocument();
}

/**
 * The shell interposes no `ConfigProvider`, so the application's own theme reaches a screen intact.
 *
 * Assumptions: the assertion is made on a RESOLVED token rather than on the absence of a provider
 * element, because a provider renders no element to look for. `ui/src/theme/antdTheme.ts` overrides
 * the `colorInfo` seed to the BMS TURQUOISE anchor, so the bridged value and antd's default differ:
 * a probe inside the shell that reported the default would be reporting that something between the
 * application's provider and the screen had re-seeded the theme. Both halves are asserted -- equals
 * the bridge, and differs from the default -- because equality alone would also hold if the bridge
 * and the default happened to agree, which would make the case vacuous.
 * @returns {Promise<void>} Resolves once the probe's resolved token has been compared.
 */
async function interposesNoThemeProviderOfItsOwn(): Promise<void> {
  await renderInAppShell(<ResolvedTokenProbe />, { initialEntries: ['/'], routePath: '/' });

  const bridged = theme.getDesignToken(cardDemoTheme).colorInfo;
  const antdDefault = theme.getDesignToken().colorInfo;

  expect(bridged).not.toBe(antdDefault);
  expect(screen.getByTestId(RESOLVED_TOKEN_TEST_ID)).toHaveTextContent(bridged);
}

/**
 * Groups the shell's zone-structure cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function shellZoneCases(): void {
  it('paints all four zones when a screen delegates them', paintsAllFourZones);
  it('paints the child route through the outlet', paintsTheChildRouteThroughTheOutlet);
  it('mounts the function-key legend in the footer zone', mountsTheKeyLegendInTheFooterZone);
  it('interposes no theme provider of its own', interposesNoThemeProviderOfItsOwn);
}

describe('application shell zones', shellZoneCases);

/**
 * Both `PIC X(40)` title constants are painted, character for character.
 *
 * Assumptions: the expected strings come from the imported catalog and are never retyped here. A
 * sentence retyped into a test asserts that the screen agrees with the TEST, which a paraphrase
 * present in both places satisfies; taken from the catalog it asserts that the screen agrees with the
 * BASELINE.
 *
 * Assumptions: the rendered form is the trimmed one and that is the contract, not a shortfall. Both
 * constants centre their text inside a 40-column 3270 field with literal spaces -- `CCDA-TITLE01`
 * carries 6 leading and 7 trailing -- and those spaces are terminal padding rather than content, so
 * the shell paints the trimmed value while the catalog keeps the padded literal. Both halves are
 * asserted: the painted text, and that the catalogued literal is still the full 40 characters.
 * @returns {Promise<void>} Resolves once both titles have been found.
 */
async function paintsBothTitleConstantsVerbatim(): Promise<void> {
  await renderShell();

  const titleBand = shellLandmark('titleBand');

  // WHY : Assumptions: `CCDA-TITLE01 PIC X(40)` at app/cpy/COTTL01Y.cpy L18-L19 and `CCDA-TITLE02
  //       PIC X(40)` at L20 and L22. The declared width is asserted against the catalog rather than
  //       against the rendered node because the painted value is trimmed.
  expect(SCREEN_TITLES.TITLE01.declaredWidth).toBe(40);
  expect(SCREEN_TITLES.TITLE02.declaredWidth).toBe(40);
  expect(SCREEN_TITLES.TITLE01.text).toHaveLength(SCREEN_TITLES.TITLE01.declaredWidth);
  expect(SCREEN_TITLES.TITLE02.text).toHaveLength(SCREEN_TITLES.TITLE02.declaredWidth);

  expect(within(titleBand).getByText(APP_ORGANISATION_TITLE_DISPLAY)).toBeInTheDocument();
  expect(within(titleBand).getByText(APP_TITLE_DISPLAY)).toBeInTheDocument();
  expect(APP_ORGANISATION_TITLE_DISPLAY).toBe(SCREEN_TITLES.TITLE01.text.trim());
  expect(APP_TITLE_DISPLAY).toBe(SCREEN_TITLES.TITLE02.text.trim());
}

/**
 * The catalogued application title is the LIVE line 22 value, not the commented-out line 21 variant.
 *
 * ⚠️ Assumptions: `app/cpy/COTTL01Y.cpy` L21 holds a DISABLED alternative reading "Credit Card Demo
 * Application (CCDA)", and only the `*` in column 7 marks it dead -- it is also exactly 40
 * characters, so it is indistinguishable from the live value by length or by shape. The live
 * `CCDA-TITLE02` value is the one on L22. This case pins the provenance as well as the text, so a
 * later "restoration" of the commented-out string fails on the recorded line number rather than
 * passing quietly because the replacement is the same width.
 * @returns {void} Nothing; the case asserts on the catalog's recorded provenance.
 */
function catalogsTheLiveTitleAndNotTheCommentedOutVariant(): void {
  // WHY : Assumptions: app/cpy/COTTL01Y.cpy -- L22 is the live literal, L21 is commented out.
  //       Asserting the recorded line is what distinguishes them, because their widths are
  //       identical.
  expect(SCREEN_TITLES.TITLE02.source.file).toBe('app/cpy/COTTL01Y.cpy');
  expect(SCREEN_TITLES.TITLE02.source.lines).toContain(22);
  expect(SCREEN_TITLES.TITLE02.source.lines).not.toContain(21);
  expect(APP_TITLE_DISPLAY).toBe('CardDemo');
  expect(SCREEN_TITLES.TITLE02.text).not.toContain('Credit Card Demo Application');
}

/**
 * The application title is a real heading rather than styled text.
 *
 * Assumptions: the query is by accessible ROLE, because that is what the assertion is about. The
 * design system's "library components over raw HTML" rule forbids a raw heading element where antd
 * supplies `Typography.Title`, and a `<div>` styled to look like a title would satisfy a text query
 * while announcing nothing to a screen reader -- which is the regression this case catches. The 3270
 * title band was identified to an operator by occupying rows 1 and 2; a heading role is what replaces
 * that cue once absolute positioning is surrendered under design gap G1.
 *
 * Assumptions: the heading LEVEL is deliberately not pinned here, and the omission is a scope
 * decision rather than a gap. The document's heading outline is one contract spanning the shell and
 * every screen, and the heading-outline suite owns it; asserting the level here as well would give
 * one contract two owners, so the next change to the outline would have two places to be updated and
 * one of them would be missed. This case owns "it is a heading", not "it is at depth n".
 * @returns {Promise<void>} Resolves once the heading has been found.
 */
async function paintsTheApplicationTitleAsAHeading(): Promise<void> {
  await renderShell();

  const heading = screen.getByRole('heading', { name: APP_TITLE_DISPLAY });

  expect(heading).toBeInTheDocument();
}

/**
 * The four per-screen header slots are painted with their measured prompts and widths.
 *
 * Assumptions: the transaction and program values are asserted at the widths the symbolic maps
 * declare -- `TRNNAMEI PIC X(4)` and `PGMNAMEI PIC X(8)`, identical on all 17 base maps in
 * `app/cpy-bms/` -- so the fixtures are values a real screen could publish rather than arbitrary
 * strings.
 *
 * ⚠️ Assumptions: the painted TIME is 8 characters because that is what `ScreenHeader` declares, and
 * the symbolic maps do not entirely agree with each other. Measured across the 17 base maps,
 * `CURTIMEI` is `PIC X(8)` on 16 and `PIC X(9)` on 1; `CURDATEI` is `PIC X(8)` on all 17. The
 * component's `HEADER_TIME_FORMAT` is a 24-hour `HH:mm:ss`, which is 8 characters and therefore
 * matches the 16-map majority. The single 9-character declaration is recorded here rather than
 * reconciled, because the discrepancy is in the baseline and picking one side silently would hide it.
 * @returns {Promise<void>} Resolves once every header slot has been found.
 */
async function paintsThePerScreenHeaderSlots(): Promise<void> {
  await renderShell();

  const titleBand = shellLandmark('titleBand');

  // WHY : Assumptions: the four prompt literals are measured from app/bms/COACTVW.bms -- `Tran:`
  //       L33, `Prog:` L56, `Date:` L46, `Time:` L69 -- and are taken from the component's exported
  //       map so the painted label and the asserted label cannot drift apart.
  expect(within(titleBand).getByText(HEADER_PROMPT_LABELS.transaction)).toBeInTheDocument();
  expect(within(titleBand).getByText(HEADER_PROMPT_LABELS.program)).toBeInTheDocument();
  expect(within(titleBand).getByText(HEADER_PROMPT_LABELS.date)).toBeInTheDocument();
  expect(within(titleBand).getByText(HEADER_PROMPT_LABELS.time)).toBeInTheDocument();

  // WHY : Assumptions: `TRNNAMEI PIC X(4)` and `PGMNAMEI PIC X(8)`, both identical on all 17 base
  //       symbolic maps under app/cpy-bms/ -- for example app/cpy-bms/COSGN00.CPY L24 and L42.
  expect(PROBE_TRANSACTION_ID).toHaveLength(4);
  expect(PROBE_PROGRAM_NAME).toHaveLength(8);
  expect(within(titleBand).getByText(PROBE_TRANSACTION_ID)).toBeInTheDocument();
  expect(within(titleBand).getByText(PROBE_PROGRAM_NAME)).toBeInTheDocument();

  // WHY : Assumptions: `CURDATEI PIC X(8)` renders `mm/dd/yy` -- the baseline builds it from
  //       `WS-CURDATE-YEAR(3:2)` at app/cbl/COSGN00C.cbl L188 into the 8-character
  //       `WS-CURDATE-MM-DD-YY` group of app/cpy/CSDAT01Y.cpy L30-L35, whose FILLERs supply the
  //       slashes. The injected instant is 18 July 2022, so the painted value is fully determined.
  expect(HEADER_DATE_FORMAT).toBe('MM/DD/YY');
  expect(within(titleBand).getByText('07/18/22')).toBeInTheDocument();

  expect(HEADER_TIME_FORMAT).toBe('HH:mm:ss');
  expect(within(titleBand).getByText('13:24:35')).toBeInTheDocument();
}

/**
 * The two CICS region slots are retired with their evidence, and nothing is painted in their place.
 *
 * Assumptions: the disposition is asserted from the component's own exported record rather than
 * restated here, so the test and the documentation cannot disagree. `APPLID` and `SYSID` are `PIC
 * X(8)` on exactly one of the 17 base mapsets and are obtained by `EXEC CICS ASSIGN` in exactly one
 * program, so 16 of 17 screens never showed either value; the CICS region has no target analogue, and
 * the migration plan retires the region's own artifacts rather than porting them.
 *
 * Alternatives Considered: repurposing the slots to carry deployment identity, which would keep two
 * populated slots. Rejected because putting a different kind of value in a slot whose baseline
 * meaning was the CICS region reads as parity while delivering something else -- a worse outcome than
 * an honest omission -- and the component records the same reasoning at its declaration.
 * @returns {Promise<void>} Resolves once both absences have been observed.
 */
async function retiresTheRegionIdentitySlots(): Promise<void> {
  await renderShell();

  const retired = RETIRED_HEADER_FIELDS.map(fieldNameOf);

  // WHY : Assumptions: `APPLIDI PIC X(8)` at app/cpy-bms/COSGN00.CPY L55-L60 and `SYSIDI PIC X(8)`
  //       at L61-L66, declared on 1 of the 17 base mapsets; the values came from EXEC CICS ASSIGN
  //       APPLID/SYSID at app/cbl/COSGN00C.cbl L198-L204.
  expect(retired).toStrictEqual(['APPLID', 'SYSID']);
  for (const entry of RETIRED_HEADER_FIELDS) {
    expect(entry.declaredWidth).toBe(8);
    expect(entry.disposition).toMatch(/^Dropped/);
  }

  const titleBand = shellLandmark('titleBand');

  expect(within(titleBand).queryByText(/APPLID/i)).not.toBeInTheDocument();
  expect(within(titleBand).queryByText(/SYSID/i)).not.toBeInTheDocument();
}

/**
 * Groups the title-band cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function titleBandCases(): void {
  it('paints both title constants verbatim', paintsBothTitleConstantsVerbatim);
  it(
    'catalogs the live title and not the commented-out variant',
    catalogsTheLiveTitleAndNotTheCommentedOutVariant,
  );
  it('paints the application title as a heading', paintsTheApplicationTitleAsAHeading);
  it('paints the per-screen header slots', paintsThePerScreenHeaderSlots);
  it('retires the CICS region identity slots', retiresTheRegionIdentitySlots);
}

describe('application shell title band', titleBandCases);

/**
 * A message short enough that no case here depends on truncation behaviour.
 *
 * Assumptions: the text is a catalogued baseline sentence rather than invented prose, so a case that
 * renders it is rendering something a real screen renders.
 */
const BAND_MESSAGE = THANK_YOU_CARDDEMO;

/**
 * The painted form of a catalogued fixed-width string: its content without the field padding.
 *
 * ⚠️ Assumptions: a catalogued entry keeps the literal exactly as the copybook holds it, padding
 * included -- `CCDA-MSG-THANK-YOU` is 49 characters against `PIC X(50)` -- and the band paints the
 * CONTENT rather than the field. That is faithful rather than lossy: COBOL pads a fixed-width field
 * with blanks that were never data, and `MessageBand` normalises them away before rendering. Passing
 * the padded literal to a text query would therefore never match, because Testing Library normalises
 * the ELEMENT's text but compares it against the expected string as given.
 *
 * Alternatives Considered: asserting against the padded literal and having the band render the
 * padding. Rejected because trailing blanks in a 3270 field are the field's width showing through,
 * not content the operator read -- rendering them would put significant-looking whitespace into the
 * accessible name of every message. The declared width is asserted separately, against the catalog's
 * `declaredWidth`, so nothing about the width contract is given up by trimming here.
 * @param {string} catalogued - The literal as the message catalog holds it, padding included.
 * @returns {string} The content a screen paints for that literal.
 */
function paintedForm(catalogued: string): string {
  return catalogued.trim();
}

/**
 * Renders the row-23 band on its own, inside the application's providers.
 * @param {string | null} message - The message text, or `null` for the reserved empty state.
 * @param {MessageBandSeverity} [severity] - Severity to announce it at; defaults to the row-23
 *   default.
 * @returns {Promise<() => void>} The render's teardown, so a case can measure two states in turn.
 */
async function renderBand(
  message: string | null,
  severity: MessageBandSeverity = 'error',
): Promise<() => void> {
  const rendered = await renderWithProviders(<MessageBand message={message} severity={severity} />);

  return rendered.unmount;
}

/**
 * The band's CONTENT contract is 75 characters, which is not its display width.
 *
 * ⚠️ Assumptions: 75 and 78 are both real and they mean different things, and this is the single
 * distinction most easily got wrong in this component. The 75 is the shared WORK AREA a message
 * travels through between pseudo-conversational turns: `CCARD-ERROR-MSG PIC X(75)` and
 * `CCARD-RETURN-MSG PIC X(75)` at `app/cpy/CVCRD01Y.cpy` L28 and L29. The 78 and 80 are the BMS
 * DISPLAY field the message is finally painted in -- measured across the 17 base symbolic maps,
 * `ERRMSGI` is `PIC X(78)` on 15 and `PIC X(80)` on 2 (`COCRDSL` and `COCRDUP`), which is 19 and 2
 * across all 21 mapsets once the four extension maps are counted. An agent reading only the symbolic
 * maps would conclude the contract is 78 or 80 and be wrong about what a message may CARRY; an agent
 * reading only the copybook would clip three characters from every screen and five from two of them.
 * Both numbers are asserted here, against the module's own exported constants, so neither can be
 * quietly substituted for the other.
 * @returns {void} Nothing; the case asserts on the exported width constants and their provenance.
 */
function boundsMessageContentAtSeventyFiveCharacters(): void {
  // WHY : Assumptions: app/cpy/CVCRD01Y.cpy L28-L30 -- the two PIC X(75) work-area fields and the
  //       off-condition. The provenance is asserted as well as the number so the citation stays
  //       checkable.
  expect(MESSAGE_BAND_CONTENT_WIDTH).toBe(75);
  expect(MESSAGE_BAND.workAreaWidth).toBe(75);
  expect(MESSAGE_BAND.sources.workArea.file).toBe('app/cpy/CVCRD01Y.cpy');
  expect(MESSAGE_BAND.sources.workArea.lines).toStrictEqual([28, 29, 30]);

  // WHY : Assumptions: the display half -- ERRMSGI PIC X(78) on 15 of the 17 base maps (e.g.
  //       app/cpy-bms/COSGN00.CPY L84) and PIC X(80) on COCRDSL and COCRDUP (e.g.
  //       app/cpy-bms/COCRDUP.CPY). Asserting they DIFFER from 75 is what pins the distinction.
  expect(MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH).toBe(78);
  expect(MESSAGE_BAND.displayWidthCardDetail).toBe(80);
  expect(MESSAGE_BAND_DEFAULT_DISPLAY_WIDTH).not.toBe(MESSAGE_BAND_CONTENT_WIDTH);
  expect(MESSAGE_BAND.displayWidthCardDetail).not.toBe(MESSAGE_BAND_CONTENT_WIDTH);
}

/**
 * The `LOW-VALUES` off-condition means "no message", so the band renders no alert.
 *
 * ⚠️ Assumptions: three forms all mean empty and all are asserted. The baseline's explicit
 * off-condition is `88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES` at `app/cpy/CVCRD01Y.cpy` L30 -- binary
 * zeros, not spaces -- while the programs also clear the field with `MOVE SPACES`. A `U+0000` string
 * survives `String.prototype.trim` with its length intact, because NUL is not whitespace, so a
 * predicate testing only for zero length would render a visible blank alert on every quiet screen.
 * @returns {Promise<void>} Resolves once the quiet band has been observed to hold no alert.
 */
async function treatsTheLowValuesSentinelAsNoMessage(): Promise<void> {
  // WHY : Assumptions: app/cpy/CVCRD01Y.cpy L30. The sentinel's NAME is carried in the catalog so
  //       the reason the NUL form is handled is recoverable from the code rather than only from
  //       this comment.
  expect(MESSAGE_BAND.emptySentinel).toBe('LOW-VALUES');
  expect(isMessageBandEmpty('')).toBe(true);
  expect(isMessageBandEmpty(null)).toBe(true);
  expect(isMessageBandEmpty('   ')).toBe(true);
  expect(isMessageBandEmpty('\u0000\u0000\u0000')).toBe(true);
  expect(isMessageBandEmpty(BAND_MESSAGE)).toBe(false);

  await renderBand(null);

  expect(messageBand()).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
}

/**
 * Showing or clearing a message causes no layout shift, because the row is always reserved.
 *
 * Assumptions: this is the browser analogue of row 23 existing on every 24-row terminal frame whether
 * or not a program had put anything in it. The two states are measured in turn and compared, and the
 * reserved height is asserted to be a non-empty value rather than only equal across the two -- a
 * component that reserved nothing would report an empty string in both states and the comparison
 * would pass vacuously, which is the failure that second assertion removes.
 *
 * Trade-offs: the height is read off the element's INLINE style rather than from
 * `getComputedStyle`, and what that gives up is real laid-out geometry. jsdom performs no layout and
 * does not resolve a CSS custom property, so a computed read returns the unresolved `var(...)` text
 * or an empty string depending on the property -- it would compare two nothings and pass whatever the
 * component did. The inline declaration is the value this component actually writes, so it is both
 * the contract and the only thing here a regression would change. Accepted knowingly: this pins that
 * the reservation is DECLARED and identical in both states, not that a browser paints it at a given
 * size, which no jsdom test can establish. Note that reading an inline style is not a geometry
 * assertion of the kind design gap G1 forbids: no offset, coordinate or character cell is involved.
 * @returns {Promise<void>} Resolves once both heights have been measured and compared.
 */
async function reservesTheRowWhetherOrNotItSpeaks(): Promise<void> {
  const unmountQuiet = await renderBand(null);
  const quietHeight = messageBand().style.blockSize;

  unmountQuiet();

  await renderBand(BAND_MESSAGE);
  const speakingHeight = messageBand().style.blockSize;

  expect(quietHeight).not.toBe('');
  expect(speakingHeight).toBe(quietHeight);
}

/**
 * An error is announced assertively, in the error variant of the design system's alert.
 *
 * Assumptions: the role is asserted as well as the variant. A refusal that a screen reader never
 * announces is not equivalent to the baseline, where the message occupied a fixed row the operator
 * was already looking at; `alert` is the role that interrupts.
 * @returns {Promise<void>} Resolves once the error band has been observed.
 */
async function announcesAnErrorAssertively(): Promise<void> {
  await renderBand(BAND_MESSAGE, 'error');

  const alert = screen.getByRole('alert');

  expect(alert.className).toContain('ant-alert-error');
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
}

/**
 * A success is announced politely, in the success variant.
 * @returns {Promise<void>} Resolves once the success band has been observed.
 */
async function announcesSuccessPolitely(): Promise<void> {
  await renderBand(BAND_MESSAGE, 'success');

  const status = screen.getByRole('status');

  expect(status.className).toContain('ant-alert-success');
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
}

/**
 * An informational message is announced politely, in the info variant.
 *
 * ⚠️ Assumptions: three severities are asserted here because three are what the design-system mapping
 * fixes for this band -- `error`, `success` and `info`. The component declares a FOURTH member,
 * `neutral`, which is an addition beyond that mapping and is owned by the band's own suite; it is
 * named here so its absence from this group reads as a scope decision rather than an oversight.
 * @returns {Promise<void>} Resolves once the informational band has been observed.
 */
async function announcesInformationPolitely(): Promise<void> {
  await renderBand(BAND_MESSAGE, 'info');

  const status = screen.getByRole('status');

  expect(status.className).toContain('ant-alert-info');
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
}

/**
 * The band carries only the screen-level message, never a whole problem document.
 *
 * ⚠️ Assumptions: the caller does the mapping, and the boundary is load-bearing rather than tidy. The
 * design system routes per-field refusals to `Form.Item validateStatus="error"` with `help` text
 * while only this band carries the screen-level line, so the band's props are a message string and a
 * severity -- it imports nothing from the API layer and accepts no `ApiError`. A future maintainer's
 * instinct will be to "simplify" by passing the whole problem object; doing so would move
 * screen-specific mapping into a shared presentational component, and the field refusals would then
 * be announced in the wrong region. This case pins the split by giving the band a real problem
 * document's `message` and asserting the sibling `fieldErrors` text does NOT appear in the band.
 * @returns {Promise<void>} Resolves once the band has been observed to carry only the screen line.
 */
async function carriesTheScreenLineButNotTheFieldRefusals(): Promise<void> {
  const fieldRefusal = 'Account ID must be an 11-digit number';
  const problem = apiError({
    message: BAND_MESSAGE,
    fieldErrors: [fieldError('accountId', fieldRefusal, 'BLANK')],
  });

  // Assumptions: `problem.message` is passed rather than `problem`, and that this compiles at all is
  //   half the assertion -- the prop's type is the message, so the extraction happens at the call
  //   site where the screen's own knowledge of its fields lives.
  await renderBand(problem.message);

  const band = messageBand();

  // Assumptions: the screen line is located through the shared verbatim matcher, which does NOT
  //   collapse internal whitespace. Several baseline strings carry doubled spaces that are content
  //   rather than formatting, and a collapsing matcher would accept a screen that emitted one space
  //   where the copybook holds two.
  expect(expectVerbatimMessage(paintedForm(BAND_MESSAGE))).toBeInTheDocument();
  expect(within(band).queryByText(fieldRefusal)).not.toBeInTheDocument();
  expect(problem.fieldErrors).toHaveLength(1);
}

/**
 * Groups the message-band cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function messageBandCases(): void {
  it('bounds message content at 75 characters', boundsMessageContentAtSeventyFiveCharacters);
  it('treats the LOW-VALUES sentinel as no message', treatsTheLowValuesSentinelAsNoMessage);
  it('reserves the row whether or not it speaks', reservesTheRowWhetherOrNotItSpeaks);
  it('announces an error assertively', announcesAnErrorAssertively);
  it('announces a success politely', announcesSuccessPolitely);
  it('announces information politely', announcesInformationPolitely);
  it(
    'carries the screen line but not the field refusals',
    carriesTheScreenLineButNotTheFieldRefusals,
  );
}

describe('application shell message band', messageBandCases);

/**
 * The `CCDA` sign-off is catalogued at 40 characters and names the application "CCDA".
 *
 * ⚠️ Assumptions: this is one of TWO sign-off strings and it is asserted on its own. `CCDA-THANK-YOU`
 * is `PIC X(40)` at `app/cpy/COTTL01Y.cpy` L23-L24 and reads "Thank you for using CCDA
 * application...". Its sibling in `app/cpy/CSMSG01Y.cpy` is `PIC X(50)` and says "CardDemo". They
 * look like one sentence stored at two widths, and de-duplicating them is the obvious tidy-up -- it
 * loses text, because neither product word appears in the other string.
 *
 * Assumptions: this constant is referenced by NO program under that name -- a repository-wide search
 * for `CCDA-THANK-YOU` outside its own copybook returns nothing -- so it is a title-band constant the
 * SPA surfaces through the shell rather than a message any transaction moves. It must survive
 * regardless; an unused baseline string is still a baseline string.
 * @returns {void} Nothing; the case asserts on the catalog entry and its provenance.
 */
function catalogsTheCcdaSignOffAtFortyCharacters(): void {
  // WHY : Assumptions: app/cpy/COTTL01Y.cpy L23-L24 -- `CCDA-THANK-YOU PIC X(40)`. The width, the
  //       product word and the provenance are all asserted, because the width alone would not
  //       distinguish this entry from a paraphrase of the same length.
  expect(THANK_YOU_CCDA).toBe(SCREEN_TITLES.THANK_YOU.text);
  expect(SCREEN_TITLES.THANK_YOU.declaredWidth).toBe(40);
  expect(SCREEN_TITLES.THANK_YOU.source.file).toBe('app/cpy/COTTL01Y.cpy');
  expect(SCREEN_TITLES.THANK_YOU.source.lines).toContain(24);
  expect(THANK_YOU_CCDA).toContain('CCDA');
  expect(THANK_YOU_CCDA).not.toContain('CardDemo');
  expect(THANK_YOU_CCDA).toHaveLength(40);
}

/**
 * The `CardDemo` sign-off is catalogued at 50 characters and names the application "CardDemo".
 *
 * ⚠️ Assumptions: the literal is 49 characters against a declared width of 50, so the source text and
 * the runtime field differ by one trailing blank COBOL supplies. The catalog stores the unpadded
 * literal and the width separately -- storing the padded form would fabricate a byte that is not in
 * the source, and storing only the text would lose the contract a renderer needs -- so both are
 * asserted here, the literal as written and the padded form reproduced on demand.
 *
 * Assumptions: this is the sign-off a program actually moves. `app/cbl/COSGN00C.cbl` L89 moves
 * `CCDA-MSG-THANK-YOU` into `WS-MESSAGE` on the PF3 path, which is why the 50-character form is the
 * one a screen displays on sign-off.
 * @returns {void} Nothing; the case asserts on the catalog entry and its provenance.
 */
function catalogsTheCardDemoSignOffAtFiftyCharacters(): void {
  // WHY : Assumptions: app/cpy/CSMSG01Y.cpy L18-L19 -- `CCDA-MSG-THANK-YOU PIC X(50)`, moved to
  //       WS-MESSAGE by app/cbl/COSGN00C.cbl L89 under WHEN DFHPF3.
  expect(THANK_YOU_CARDDEMO).toBe(COMMON_MESSAGES.THANK_YOU.text);
  expect(COMMON_MESSAGES.THANK_YOU.declaredWidth).toBe(50);
  expect(COMMON_MESSAGES.THANK_YOU.source.file).toBe('app/cpy/CSMSG01Y.cpy');
  expect(COMMON_MESSAGES.THANK_YOU.source.lines).toContain(19);
  expect(THANK_YOU_CARDDEMO).toContain('CardDemo');
  expect(THANK_YOU_CARDDEMO).not.toContain('CCDA');
  expect(THANK_YOU_CARDDEMO).toHaveLength(49);
  expect(
    padToDeclaredWidth(THANK_YOU_CARDDEMO, COMMON_MESSAGES.THANK_YOU.declaredWidth),
  ).toHaveLength(50);
}

/**
 * The two sign-off strings are distinct entries and no comparison reconciles them.
 *
 * ⚠️ Alternatives Considered: asserting the sign-off text against a string literal retyped into this
 * file. Rejected because it inverts what the case proves. A paraphrase introduced into the shell and
 * the same paraphrase retyped here would AGREE, so the case would pass while the verbatim guarantee
 * was gone; taken from the catalog, a screen-side paraphrase fails immediately. That is the whole
 * reason `ui/src/messages/messages.ts` exists as the single source for user-visible text.
 *
 * ⚠️ Alternatives Considered: de-duplicating the pair by comparing their trimmed forms, which is the
 * tidy-up a reader reaches for on first seeing two near-identical sentences. Rejected and pinned
 * against here: the trimmed forms differ in the product word, so a merge would silently drop one of
 * two strings the baseline displays. A width-based merge fails too -- 40 against 50.
 * @returns {void} Nothing; the case asserts the two entries cannot be collapsed.
 */
function keepsTheTwoSignOffStringsDistinct(): void {
  expect(THANK_YOU_CCDA).not.toBe(THANK_YOU_CARDDEMO);
  expect(paintedForm(THANK_YOU_CCDA)).not.toBe(paintedForm(THANK_YOU_CARDDEMO));
  expect(SCREEN_TITLES.THANK_YOU.declaredWidth).not.toBe(COMMON_MESSAGES.THANK_YOU.declaredWidth);
  expect(SCREEN_TITLES.THANK_YOU.source.file).not.toBe(COMMON_MESSAGES.THANK_YOU.source.file);
}

/**
 * The shell never substitutes one sign-off string for the other.
 *
 * Assumptions: the substitution is checked in the rendered band rather than only between the two
 * catalog entries, because a merge would most plausibly happen at the point of USE -- a screen
 * reaching for whichever constant it remembered. Painting one and asserting the other is absent is
 * what catches that.
 * @returns {Promise<void>} Resolves once the painted band has been observed to hold only one of them.
 */
async function neverSubstitutesOneSignOffForTheOther(): Promise<void> {
  await renderBand(THANK_YOU_CARDDEMO, 'info');

  const band = messageBand();

  expect(within(band).getByText(paintedForm(THANK_YOU_CARDDEMO))).toBeInTheDocument();
  expect(within(band).queryByText(paintedForm(THANK_YOU_CCDA))).not.toBeInTheDocument();
}

/**
 * Groups the two sign-off strings, deliberately in separate cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function signOffStringCases(): void {
  it('catalogs the CCDA sign-off at 40 characters', catalogsTheCcdaSignOffAtFortyCharacters);
  it(
    'catalogs the CardDemo sign-off at 50 characters',
    catalogsTheCardDemoSignOffAtFiftyCharacters,
  );
  it('keeps the two sign-off strings distinct', keepsTheTwoSignOffStringsDistinct);
  it('never substitutes one sign-off for the other', neverSubstitutesOneSignOffForTheOther);
}

describe('application shell sign-off strings', signOffStringCases);

/**
 * Both baseline validation flag states survive as the field-error discriminator.
 *
 * Assumptions: the two states are exactly the pair the templated copybook branches on --
 * `app/cpy/CSSETATY.cpy` L18-L19 tests `FLG-(TESTVAR1)-NOT-OK OR FLG-(TESTVAR1)-BLANK` -- and the
 * distinction is load-bearing rather than decorative, because L23-L25 gives the BLANK case an extra
 * `'*'` marker the not-OK case does not get. A target model with one "invalid" state could not
 * express that.
 * @returns {void} Nothing; the case asserts on the two modelled states.
 */
function modelsBothBaselineValidationStates(): void {
  // WHY : Assumptions: app/cpy/CSSETATY.cpy L18-L19 -- the two condition names the highlight
  //       branches on.
  const notOk: FieldValidationState = 'NOT_OK';
  const blank: FieldValidationState = 'BLANK';

  expect(fieldError('accountId', 'refused', notOk).state).toBe('NOT_OK');
  expect(fieldError('accountId', 'refused', blank).state).toBe('BLANK');
  expect(notOk).not.toBe(blank);
}

/**
 * A refused field renders as an errored `Form.Item` carrying its refusal as help text.
 *
 * Assumptions: this is the browser target of the baseline's red-attribute highlight. Where
 * `app/cpy/CSSETATY.cpy` L21-L22 moves `DFHRED` into the field's colour attribute, the target sets
 * `validateStatus="error"` on the `Form.Item` and supplies the refusal sentence as `help`, which antd
 * renders in its explain container and colours from the theme.
 *
 * Assumptions: the refusal is supplied straight as `help`, which is the shared SHAPE this case owns.
 * The separate question of how a refusal element is wired to its control by identifier -- so that
 * `aria-describedby` and the element's `id` are generated together and cannot drift apart -- belongs
 * to the field-help module and its own suite, not to the shell.
 *
 * ⚠️ Refactoring Rationale: the baseline gates this highlight on `CDEMO-PGM-REENTER`
 * (`app/cpy/CSSETATY.cpy` L20), the pseudo-conversational re-entry discriminator declared at
 * `app/cpy/COCOM01Y.cpy` L29-L31. That gate has NO target analogue and none is asserted here,
 * because the discriminator disappears entirely: a stateless handler that answers with a field-error
 * array has no first-entry-versus-re-entry distinction to make, so the highlight is driven purely by
 * the response body. A maintainer who does not know this will look for the missing gate and conclude
 * the port is incomplete.
 * @returns {Promise<void>} Resolves once the errored field has been observed.
 */
async function rendersARefusalAsErroredHelpText(): Promise<void> {
  const controlId = 'accountId';
  const refusal = 'Account ID must be an 11-digit number';

  await renderWithProviders(
    <Form>
      <Form.Item help={refusal} validateStatus="error">
        <Input id={controlId} />
      </Form.Item>
    </Form>,
  );

  const help = screen.getByText(refusal);

  expect(help).toBeInTheDocument();
  expect(help.closest('.ant-form-item-explain-error')).not.toBeNull();
}

/**
 * The blank case keeps the literal `'*'` marker, and its colour resolves through a token.
 *
 * ⚠️ Assumptions: the marker is a real and easily-dropped detail. `app/cpy/CSSETATY.cpy` L24 moves
 * the literal `'*'` into the field itself when the flag is BLANK -- on top of the red attribute, not
 * instead of it -- so a target that only reddened the field would lose a character the operator saw.
 * The marker is held in the theme bridge beside the colour it is painted in, which is why both halves
 * of the contract are asserted from the same constant.
 *
 * Assumptions: the colour is asserted to be a token NAME rather than a colour value, which is what
 * the design system's "zero hardcoded values" rule requires. The name is checked to resolve against
 * the application's own theme, so a token that had been renamed out of existence would fail here
 * rather than silently paint nothing.
 *
 * Assumptions: the per-screen PAINTING of the marker is not asserted here. Each screen owns which of
 * its fields can be blank, and the screen suites assert their own; this case owns the shared contract
 * the screens draw on.
 * @returns {void} Nothing; the case asserts on the shared field-error tokens.
 */
function keepsTheBlankMarkerAndResolvesItsColourThroughAToken(): void {
  // WHY : Assumptions: app/cpy/CSSETATY.cpy L24 -- `MOVE '*' TO (SCRNVAR2)O OF (MAPNAME3)O` for the
  //       BLANK case.
  expect(FIELD_ERROR_TOKENS.blankMarker).toBe('*');

  // WHY : Assumptions: app/cpy/CSSETATY.cpy L21 moves DFHRED into the colour attribute. The target
  //       carries the role as a token name so the theme decides the shade; a literal would defeat
  //       the bridge.
  const designToken = theme.getDesignToken(cardDemoTheme);

  expect(FIELD_ERROR_TOKENS.errorColor).not.toMatch(/^(#|rgb|hsl)/);
  expect(FIELD_ERROR_TOKENS.errorColor in designToken).toBe(true);
  expect(String(designToken[FIELD_ERROR_TOKENS.errorColor])).not.toBe('');
}

/**
 * Groups the shared field-error cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function fieldErrorCases(): void {
  it('models both baseline validation states', modelsBothBaselineValidationStates);
  it('renders a refusal as errored help text', rendersARefusalAsErroredHelpText);
  it(
    'keeps the blank marker and resolves its colour through a token',
    keepsTheBlankMarkerAndResolvesItsColourThroughAToken,
  );
}

describe('application shell field-error contract', fieldErrorCases);

/**
 * The shell holds no session state of its own; identity is read from the auth hook.
 *
 * ⚠️ Assumptions: there is no auth context provider anywhere in this tree and none is invented here.
 * The hook keeps its session in module scope and surfaces it through an external store, so a reading
 * taken outside any provider is the same reading a component inside the shell gets. That is the
 * observable consequence of the shell holding nothing: were the shell the owner of identity, a
 * reading taken outside it could not agree with one taken inside it.
 * @returns {void} Nothing; the case asserts on the reading the hook publishes.
 */
function readsIdentityFromTheHookRatherThanHoldingIt(): void {
  const { result } = renderHook(useAuth);

  expect(result.current.signedOn).toBe(false);
  expect(result.current.isAuthenticated).toBe(false);
  expect(result.current.isAdmin).toBe(false);
  expect(result.current.userId).toBeNull();
  expect(result.current.groups).toHaveLength(0);
}

/**
 * A test cannot grant itself authority by writing to the client-side reading.
 *
 * ⚠️ Assumptions: in the baseline the COMMAREA was storage the CLIENT echoed back --
 * `CDEMO-USER-TYPE PIC X(01)` with its `'A'` and `'U'` condition names at `app/cpy/COCOM01Y.cpy`
 * L26-L28 -- so a client could in principle assert its own user type. In the target the group claim
 * is signed and the client cannot assert anything: the hook publishes no setter for the groups, the
 * user type or the administrator flag, and every reading it hands out is frozen. That is a genuine
 * security improvement rather than a port, and this case is what stops a later convenience setter
 * from reintroducing the weaker path.
 *
 * Assumptions: the absence is asserted by shape as well as by freezing -- no published member is a
 * setter for authority -- because a setter that mutated the module store instead of the snapshot
 * would leave the snapshot frozen and still grant admin.
 * @returns {void} Nothing; the case asserts the reading cannot be written to.
 */
function refusesToLetAClientAssertItsOwnAuthority(): void {
  const { result } = renderHook(useAuth);
  const published = Object.keys(result.current);

  expect(published.filter(looksLikeASetter)).toStrictEqual([]);
  expect(published).toContain('signIn');
  expect(published).toContain('signOut');

  // Assumptions: the snapshot is typed as a bare object for this assertion so the write is attempted
  //   at run time rather than rejected by the compiler. The compile-time refusal is real and welcome,
  //   but it is not what protects a JavaScript caller, so the runtime half is what is pinned.
  const snapshot: object = result.current;

  /**
   * Attempts to write an administrator flag onto the published reading.
   * @returns {void} Nothing; the write is expected to throw before it returns.
   * @throws {TypeError} Always, because the reading is frozen.
   */
  function grantAdminDirectly(): void {
    Object.assign(snapshot, { isAdmin: true });
  }

  expect(grantAdminDirectly).toThrow(TypeError);
  expect(Object.isFrozen(result.current.groups)).toBe(true);
  expect(result.current.isAdmin).toBe(false);
}

/**
 * Authority comes from the signed claim, established through the identity helper.
 *
 * Assumptions: the session is established by driving the real sign-on exchange rather than by seeding
 * a group directly, because that is the only path to authority production has. A helper that assigned
 * a group would create a second, weaker path and the case would then prove something the application
 * cannot do.
 * @returns {Promise<void>} Resolves once the administrator session has been observed.
 */
async function derivesAuthorityFromTheSignedClaim(): Promise<void> {
  const session = await seedSession({ groups: [CARDDEMO_ADMIN_GROUP] });

  expect(session.result.current.signedOn).toBe(true);
  expect(session.result.current.isAdmin).toBe(true);
  expect(session.result.current.groups).toContain(CARDDEMO_ADMIN_GROUP);
}

/**
 * Groups the stateless-shell cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function statelessShellCases(): void {
  it(
    'reads identity from the hook rather than holding it',
    readsIdentityFromTheHookRatherThanHoldingIt,
  );
  it('refuses to let a client assert its own authority', refusesToLetAClientAssertItsOwnAuthority);
  it('derives authority from the signed claim', derivesAuthorityFromTheSignedClaim);
}

describe('application shell statelessness', statelessShellCases);

/**
 * The zones appear in the baseline's reading order, top band to key legend.
 *
 * ⚠️ Assumptions: ORDER is asserted and geometry is not, and the split is the architecture's rather
 * than a convenience. Design gap G1 surrenders pixel-for-character positioning because reproducing a
 * 24x80 character grid in a browser would be hostile to accessibility and impossible to make
 * responsive; what it undertakes to preserve is field grouping, reading order and tab order. Document
 * position is exactly that undertaking made checkable, so this case is the assertion G1 permits and a
 * measured offset is the assertion it forbids.
 *
 * Assumptions: the message line precedes the key legend because that is the measured baseline order
 * -- the message field sits at row 23 and the legend at row 24 on all 17 base mapsets -- and because
 * after a refusal an operator reads the explanation before the key list, so inverting the pair would
 * put the explanation below the controls it explains.
 * @returns {Promise<void>} Resolves once the document order has been compared.
 */
async function keepsTheZonesInReadingOrder(): Promise<void> {
  await renderShell(BAND_MESSAGE, 'error');

  const zones = [
    shellLandmark('titleBand'),
    shellLandmark('screenBody'),
    messageBand(),
    shellLandmark('keyLegend'),
  ];

  for (let index = 0; index + 1 < zones.length; index += 1) {
    const earlier = zones[index];
    const later = zones[index + 1];

    // Assumptions: `noUncheckedIndexedAccess` makes each element possibly-undefined, so the pair is
    //   narrowed rather than asserted non-null -- an unchecked cast is what this compiler option
    //   exists to prevent, and a genuinely short list should fail the loop rather than be silenced.
    expect(earlier).toBeDefined();
    expect(later).toBeDefined();
    if (earlier === undefined || later === undefined) {
      continue;
    }

    expect(earlier.compareDocumentPosition(later) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  }
}

/**
 * The content region is reachable from the keyboard through a skip link that can hold focus.
 *
 * ⚠️ Assumptions: full keyboard operability is a FIDELITY requirement here, not an accessibility
 * extra. The 3270 terminal had no pointer at all, so every action an operator could take was a
 * keystroke; a shell reachable only by mouse would be a functional regression against the baseline
 * rather than a missing enhancement.
 *
 * Assumptions: the target is asserted to be focusable as well as present. A fragment jump scrolls to
 * its target but only MOVES focus when the target can hold it, so a skip link pointing at a
 * non-focusable region would move the viewport and leave the keyboard in the header -- the next Tab
 * would return to where it started.
 * @returns {Promise<void>} Resolves once the link and its focusable target have been observed.
 */
async function reachesTheContentRegionFromTheKeyboard(): Promise<void> {
  const { user } = await renderInAppShell(<ProbeScreen message={null} severity="error" />, {
    initialEntries: ['/'],
    routePath: '/',
  });

  const skipLink = screen.getByRole('link', { name: SKIP_TO_CONTENT_LABEL });

  expect(skipLink).toHaveAttribute('href', `#${SHELL_CONTENT_ELEMENT_ID}`);

  await user.tab();

  expect(skipLink).toHaveFocus();

  const content = shellLandmark('screenBody');

  expect(content).toHaveAttribute('id', SHELL_CONTENT_ELEMENT_ID);
  expect(content).toHaveAttribute('tabindex', '-1');
}

/**
 * The abend surface is modelled as four field widths, because the copybook carries no text.
 *
 * ⚠️ Assumptions: this group contributes WIDTHS and not messages. All four fields of `ABEND-DATA` at
 * `app/cpy/CSMSG02Y.cpy` L21-L29 are declared `VALUE SPACES`, so the copybook supplies a structure a
 * failing program fills at run time; the displayed wording comes from the programs and is catalogued
 * separately. Treating the copybook as a source of text would yield four empty strings and hide where
 * the real wording lives, so no message is asserted here.
 * @returns {void} Nothing; the case asserts on the catalogued abend field widths.
 */
function catalogsTheAbendFieldWidths(): void {
  // WHY : Assumptions: app/cpy/CSMSG02Y.cpy L21-L29 -- ABEND-CODE X(4) L22, ABEND-CULPRIT X(8) L24,
  //       ABEND-REASON X(50) L26, ABEND-MSG X(72) L28. The file is 35 lines long, so the migration
  //       plan's "L45-L53" citation cannot be right; these are the verified positions.
  expect(ABEND_DATA_FIELDS.map(fieldNameOf)).toStrictEqual([
    'ABEND-CODE',
    'ABEND-CULPRIT',
    'ABEND-REASON',
    'ABEND-MSG',
  ]);
  expect(ABEND_DATA_FIELDS.map(declaredWidthOf)).toStrictEqual([4, 8, 50, 72]);

  for (const entry of ABEND_DATA_FIELDS) {
    expect(entry.source.file).toBe('app/cpy/CSMSG02Y.cpy');
    expect(entry.source.lines.length).toBeGreaterThan(0);
  }
}

/**
 * Groups the accessibility, keyboard-fidelity and abend-surface cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function accessibilityCases(): void {
  it('keeps the zones in reading order', keepsTheZonesInReadingOrder);
  it('reaches the content region from the keyboard', reachesTheContentRegionFromTheKeyboard);
  it('catalogs the abend field widths', catalogsTheAbendFieldWidths);
}

describe('application shell accessibility and keyboard fidelity', accessibilityCases);
