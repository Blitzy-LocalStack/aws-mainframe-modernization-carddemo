/**
 * @file Component tests for the account view screen, `ui/src/screens/accountView/index.tsx` --
 * the migration target of BMS mapset `app/bms/COACTVW.bms` (map `CACTVWA`, `DFHMDI SIZE=(24,80)`,
 * 100 `DFHMDF` definitions of which 37 are named) and program `app/cbl/COACTVWC.cbl`, mounted by
 * `ui/src/router.tsx` at `/account/view`.
 *
 * Purpose
 * -------
 * Cover the contracts this screen carries across from the reference that no other check can reach:
 * the single unprotected control at its declared width and its digit-only alphabet, the one cursor
 * position the mapset sets, the record rendered through the design system's record view, the five
 * monetary amounts as fixed-pitch strings, the twelve message sentences byte-for-byte from the
 * catalog, the two attention identifiers the program admits and no others, the two protected
 * identifiers in their masked form only, the selected account travelling as an explicit request
 * value rather than as session state, and the field-error treatment in both of its origins.
 *
 * Parameters
 * ----------
 * A test module takes no arguments. Its inputs are the fixtures {@link accountView} and
 * {@link customerDetail} build, the problem documents `apiError` and `fieldError` build in
 * `ui/src/test/setup.ts`, and the answers each case programs into the mocked account transport.
 *
 * Returns
 * -------
 * Nothing. Each case registers with the runner and reports through its assertions; the module's
 * observable result is the pass or fail of the cases {@link accountViewCases} registers.
 *
 * Exceptions or errors
 * --------------------
 * No case throws deliberately. Testing Library raises on a query that matches nothing, `pressPfKey`
 * raises for an attention identifier with no browser key, and the runner reports either as a
 * failure of the case that provoked it.
 *
 * Why this file is the only verification this screen gets
 * ------------------------------------------------------
 * `tests/README.md` section 1.1 states it directly: "Online `CO*` CICS programs cannot run
 * end-to-end without a CICS runtime (absent on the runner); only their extractable field-validation
 * logic is unit-tested." The COBOL parity oracle therefore covers the BATCH chain and there is no
 * golden master for `COACTVWC` anywhere in `tests/golden/**` -- so nothing downstream re-checks the
 * behaviours below, and a fidelity claim this file does not assert is a claim nothing asserts.
 *
 * How this file is documented
 * ---------------------------
 * Two obligations govern it and they agree, so this extends an established house convention rather
 * than introducing one. The project's single user-specified rule, Rule 1 (Explainability), requires
 * a docstring stating purpose, parameters, return values and exceptions on every function and
 * module entry point, and an inline comment justifying every non-obvious decision under one of
 * `Alternatives Considered:`, `Refactoring Rationale:`, `Assumptions:` or `Trade-offs:`.
 * `tests/README.md` section 12 imposes the identical obligation on "every new test, fixture builder,
 * helper, mock, and runner routine" and calls it "a hard review gate". Because the two agree, the
 * form used here is the one `docs/CODE_DOCUMENTATION_STANDARD.md` fixes for TypeScript, and every
 * number or string taken from a copybook, mapset or program carries its file and line beside it --
 * an uncited width would be a magic number, which is Rule 1's first forbidden pattern.
 */

/*
 * WHY : Assumptions: the runner's API is IMPORTED rather than read from an ambient global, and the
 *       import is what makes this file compile. `ui/tsconfig.json` sets `"types": []`, so nothing is
 *       declared ambiently and a bare `describe` is `TS2593: Cannot find name` -- verified by
 *       compiling this file both ways. `ui/vitest.config.ts` sets `globals: true` at L136 and the
 *       block above it states the pairing outright: the two settings sit on opposite sides so that a
 *       stray `expect` in a SCREEN is a named typecheck failure, and "every test file continues to
 *       import what it uses by name ... The imports are what satisfy the compiler under the empty
 *       types list". The injection only decides what exists at run time inside the runner.
 * WHY : Alternatives Considered: relying on the injection and omitting the import, which the
 *       `globals: true` setting appears on its own to permit. Rejected on the compile result above:
 *       it fails `npm run typecheck`, which `.github/workflows/ui-ci.yml` runs as a required step,
 *       so the file would not build at all.
 */
import { describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';

import { readAccountView } from '../api/accounts';
import type { AccountViewResponse, CustomerDetail } from '../api/accounts';
import { INFORMATION_BAND_TEST_ID, MESSAGE_BAND_TEST_ID } from '../layout/MessageBand';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import {
  ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS,
  ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS,
  ACCOUNT_VIEW_KEY_LABELS,
  INVALID_KEY_PRESSED,
  PROGRAM_MESSAGES,
  STATUS_MESSAGES,
} from '../messages/messages';
import { ACCOUNT_VIEW_PATH } from '../router';
import { AccountViewScreen } from '../screens/accountView';
import { FIELD_ERROR_TOKENS, TYPOGRAPHY_TOKENS } from '../theme/tokens';
import {
  apiError,
  expectMaxLength,
  expectVerbatimMessage,
  fieldError,
  pressPfKey,
  renderInAppShell,
  shellLandmark,
} from './setup';
import type { HarnessRenderResult } from './setup';

/**
 * The twelve sentences `app/cbl/COACTVWC.cbl` declares, as the catalog holds them.
 *
 * Assumptions: every sentence is read from here and none is retyped, which is the whole of the
 * fidelity guarantee. Transformation rule T8 carries each string across character for character, so
 * a case that retyped one would assert the screen agrees with the TEST -- and a paraphrase written
 * into both places satisfies that while the operator sees the wrong words.
 */
const VIEW_MESSAGES = STATUS_MESSAGES.COACTVWC;

/**
 * The refusal the program actually moves for a malformed filter.
 *
 * Assumptions: this is a THIRD literal and not a duplicate of the two `88`-level values below it.
 * `app/cbl/COACTVWC.cbl` L672 moves `Account Filter must  be a non-zero 11 digit number` -- a double
 * space after "must", "non-zero" hyphenated -- whereas L126 and L128 declare
 * `Account number must be a non zero 11 digit number` with a single space and no hyphen. The two
 * condition names are declared and `SET` nowhere, so L672 is the sentence an operator sees.
 */
const ACCOUNT_FILTER_REFUSAL =
  PROGRAM_MESSAGES.COACTVWC.ACCOUNT_FILTER_MUST_BE_A_NON_ZERO_11_DIGIT_NUMBER;

/**
 * A well-formed account identifier at the declared width.
 *
 * Assumptions: eleven digits with leading zeros, which is the shape the reference's own seed data
 * carries and the shape that would lose information if it were ever held as a number.
 */
const ACCOUNT_ID = '00000000011';

/**
 * Declared width of the account filter, in characters.
 *
 * Assumptions: three independent reference declarations fix eleven, so it is a contract rather than
 * a preference -- `ACCTSIDI PIC 99999999999` at `app/cpy-bms/COACTVW.CPY` L60, `LENGTH=11` with
 * `PICIN='99999999999'` and `VALIDN=(MUSTFILL)` at `app/bms/COACTVW.bms` L84-L90, and
 * `CDEMO-ACCT-ID PIC 9(11)` at `app/cpy/COCOM01Y.cpy` L38.
 */
const ACCOUNT_ID_DECLARED_WIDTH = 11;

/**
 * Declared width of each of the five monetary fields, in characters.
 *
 * Assumptions: `ACRDLIMI`, `ACSHLIMI`, `ACURBALI`, `ACRCYCRI` and `ACRCYDBI` are each `PIC X(15)` at
 * `app/cpy-bms/COACTVW.CPY` L78, L90, L102, L108 and L120, and the mapset gives all five
 * `PICOUT='+ZZZ,ZZZ,ZZZ.99'` -- an edit mask whose output is exactly fifteen characters. The width
 * is therefore checkable on the rendered value, which is what {@link expectEditMaskedMoney} does.
 */
const MONEY_FIELD_DECLARED_WIDTH = 15;

/**
 * Identifier the screen joins the filter's label to its control with.
 *
 * Assumptions: the value is the screen's own `ACCOUNT_ID_FIELD_ID`, which is module-private there,
 * so it is restated here rather than imported. It is asserted rather than merely used: it is the
 * stem `ui/src/layout/fieldHelp.tsx` derives the refusal element's identifier from, so a case
 * checking that `aria-describedby` resolves has to know it.
 */
const ACCOUNT_ID_CONTROL_ID = 'account-view-account-id';

/**
 * Response member the service names when it refuses the submitted filter.
 *
 * Assumptions: `ui/src/api/accounts.ts` documents that a rejected read answers with a `fieldErrors`
 * entry keyed `accountId`, and the screen routes an entry to its control by matching that key. A
 * fixture using any other key exercises the unmatched arm instead.
 */
const ACCOUNT_ID_RESPONSE_FIELD = 'accountId';

/**
 * Builds the mocked surface of the account transport module.
 *
 * Assumptions: this is a hoisted function DECLARATION rather than an inline factory held in a
 * `const`, which is what makes it usable at all -- the runner lifts every `vi.mock` call above the
 * imports, so a factory in a `const` would be in its temporal dead zone at registration time.
 *
 * Assumptions: only `readAccountView` is stubbed, because it is the only member of that module this
 * screen calls. Stubbing the module's other operations would describe a dependency the screen does
 * not have.
 * @returns {Record<string, unknown>} The transport surface, its one operation a fresh spy.
 */
function mockAccountTransportModule(): Record<string, unknown> {
  return { readAccountView: vi.fn() };
}

/*
 * WHY : Alternatives Considered: intercepting HTTP instead of the transport module. No
 *       request-interception library is declared in `ui/package.json`, so the seam available is the
 *       function the screen calls -- and it is
 *       also the honest one for these cases, which assert what a SCREEN does with an outcome.
 *       Going through the client would additionally exercise the interceptor chain and make a
 *       presentation regression indistinguishable from a transport one.
 * WHY : Assumptions: no network endpoint and no credential appear anywhere below. The mock resolves
 *       and rejects locally, so nothing here reaches a socket or reads a secret.
 */
vi.mock('../api/accounts', mockAccountTransportModule);

/**
 * Collapses runs of whitespace the way Testing Library's default normaliser does.
 *
 * Assumptions: the catalog holds each label and sentence with the interior and trailing pad its
 * fixed-width field declared, and Testing Library normalises the DOM side while taking the expected
 * string as given -- so a padded literal never matches its own rendering. Collapsing applies one
 * normalisation to both sides while keeping the catalog as the single source of the text.
 * @param {string} value - A catalogued literal carrying its declared pad.
 * @returns {string} The same text with whitespace runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * A populated customer, with both protected identifiers already masked.
 *
 * Assumptions: the two sensitive members arrive ALREADY masked because that is what the service
 * returns -- this migration's data-exposure narrowing stores the national identifier and the
 * government-issued reference encrypted and returns them masked, so `ui/src/api/types.ts` declares
 * the members `ssnMasked` and `governmentIssuedIdMasked` and no unmasked member exists to carry the
 * digits. Building the fixture this way makes the exposure property hold by CONSTRUCTION: a case
 * cannot accidentally assert an unmasked value onto the screen, because none is representable.
 *
 * Assumptions: no card verification value appears, and none can. `ui/src/api/types.ts` declares no
 * such member on either record, so the fixture could not carry one even deliberately -- which is
 * the strongest form the "never rendered" contract can take.
 *
 * Assumptions: the values themselves are ILLUSTRATIVE and carry no citation, because they are
 * fixture data rather than transcribed constants -- no assertion depends on any particular one, only
 * on each being distinguishable from the others so that a value rendered against the wrong label is
 * visible. The two telephone members are nonetheless written at thirteen characters, which is the
 * width the MAP field declares -- `ACSPHN1I` and `ACSPHN2I` are `PIC X(13)` at
 * `app/cpy-bms/COACTVW.CPY` L204 and L216, narrower than the `CUST-PHONE-NUM-1` and
 * `CUST-PHONE-NUM-2` record fields at `PIC X(15)` in `app/cpy/CVCUS01Y.cpy` L15-L16. The display
 * width is the one that governs here, so a fixture cannot carry a value the terminal could not have
 * shown.
 * @returns {CustomerDetail} The customer half of a composed read.
 */
function customerDetail(): CustomerDetail {
  return {
    customerId: '000000011',
    ssnMasked: '***-**-6789',
    dateOfBirth: '1980-07-04',
    ficoCreditScore: '742',
    firstName: 'PAUL',
    middleName: 'T',
    lastName: 'BUCK',
    addressLine1: '742 EVERGREEN TERRACE',
    stateCode: 'IL',
    addressLine2: 'APT 4B',
    zipCode: '60007',
    city: 'ELK GROVE',
    countryCode: 'USA',
    phoneNumber1: '(312)555-0101',
    governmentIssuedIdMasked: '****4321',
    phoneNumber2: '(312)555-0202',
    eftAccountId: '00000000012345678901',
    primaryCardHolderIndicator: 'Y',
  };
}

/**
 * A composed account view, populated so every rendered member is distinguishable.
 *
 * Assumptions: every one of the five amounts is a `string` and none is a `number`.
 * `ui/src/api/types.ts` declares `Money` as `string` precisely so no client routes an exact decimal
 * through an IEEE-754 double, and the amounts chosen exercise the edit mask's grouping boundary --
 * `5000.00` groups a comma in, `75.25` does not.
 *
 * Assumptions: both message members are `null`, which the contract permits and which puts the
 * screen on its documented fallback rather than on a service-supplied sentence. That is the arm
 * worth fixing in a fixture: the reference's information line is the prompt in every state, because
 * `WS-INFORM-OUTPUT` at `app/cbl/COACTVWC.cbl` L115-L116 is declared and `SET` nowhere while
 * L528-L530 restores the prompt whenever the field is empty.
 * @returns {AccountViewResponse} The composed read a successful lookup returns.
 */
function accountView(): AccountViewResponse {
  return {
    accountId: ACCOUNT_ID,
    account: {
      activeStatus: 'Y',
      openDate: '2015-03-01',
      creditLimit: '5000.00',
      expirationDate: '2026-03-01',
      cashCreditLimit: '1500.00',
      reissueDate: '2023-03-01',
      currentBalance: '1234.56',
      currentCycleCredit: '250.00',
      groupId: 'ZEROAPR',
      currentCycleDebit: '75.25',
    },
    customer: customerDetail(),
    informationMessage: null,
    returnMessage: null,
  };
}

/**
 * Renders the screen inside the real application shell, at its own route.
 *
 * Assumptions: the shell is present rather than the screen being rendered bare, and it has to be.
 * This screen DELEGATES its title band, its row-23 error line and its row-24 key legend to the one
 * `AppShell` that `ui/src/App.tsx` mounts, publishing all three through `useShellSlot` -- so a bare
 * screen paints none of them and every case reading the legend or the error band would fail for the
 * wrong reason, reporting a missing element where the value was in fact correct.
 *
 * Assumptions: the route pattern is the one `ui/src/router.tsx` declares, `/account/view`, and it
 * carries no parameter. The account identifier is keyed on the screen rather than taken from the
 * address, so mounting at the real pattern asserts that the screen needs nothing from the route.
 * @returns {Promise<HarnessRenderResult>} The render result, with the operator attached.
 */
async function renderScreen(): Promise<HarnessRenderResult> {
  return renderInAppShell(<AccountViewScreen />, {
    routePath: ACCOUNT_VIEW_PATH,
    initialEntries: [ACCOUNT_VIEW_PATH],
  });
}

/**
 * Reads the account filter, which the mapset labels with its own painted caption.
 *
 * Assumptions: the control is found by its accessible LABEL rather than by a test identifier or a
 * CSS class, because the label is the property an operator and an assistive technology both act on.
 * The caption is `Account Number :` from the catalog, transcribed from `app/bms/COACTVW.bms`.
 * @returns {HTMLElement} The filter control.
 */
function filterControl(): HTMLElement {
  return screen.getByLabelText(collapse(ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.ACCOUNT_NUMBER));
}

/**
 * Reads one element's text with its whitespace collapsed.
 * @param {Element} element - The element whose text is read.
 * @returns {string} The collapsed text.
 */
function collapsedTextOf(element: Element): string {
  return collapse(element.textContent ?? '');
}

/**
 * Returns the legend region the shared key bar paints inside the shell's footer.
 *
 * Assumptions: the region is located by its accessible name rather than by position, because
 * `ui/src/layout/PfKeyBar.tsx` gives the bar `aria-label={PF_KEY_BAR_REGION_LABEL}` and that name is
 * the handle a caller is meant to use.
 * @returns {HTMLElement} The legend region.
 */
function legendRegion(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Reads the legend's controls as collapsed labels, in painted order.
 *
 * Refactoring Rationale: the projection is a named function rather than an inline `map` at each call
 * site. `ui/eslint.config.js` selects an arrow function in every position through its
 * `* > ArrowFunctionExpression` context with `publicOnly: false`, so each inline callback would owe
 * its own JSDoc block -- and the call sites would then each carry a block documenting the same
 * one-line projection.
 * @returns {readonly string[]} The collapsed label of every control the legend paints.
 */
function legendLabels(): readonly string[] {
  return Array.from(legendRegion().querySelectorAll('button')).map(collapsedTextOf);
}

/**
 * Activates the legend control carrying one painted label.
 *
 * Assumptions: the control is reached through the legend region rather than by role and name over
 * the whole document, so a control painted elsewhere could not satisfy a case about the legend.
 * @param {HarnessRenderResult} rendered - The render result, for its operator.
 * @param {string} label - The legend label as the mapset paints it.
 * @returns {Promise<void>} Resolves once the control has been activated.
 * @throws {Error} If the legend paints no control with that label, which Testing Library raises.
 */
async function pressLegendControl(rendered: HarnessRenderResult, label: string): Promise<void> {
  const control = within(legendRegion()).getByRole('button', { name: collapse(label) });
  await rendered.user.click(control);
}

/**
 * Reads the row-22 informational band the view mapset declares.
 *
 * Assumptions: the row-22 line and the row-23 line are addressed by DIFFERENT identifiers, because
 * `app/cpy-bms/COACTVW.CPY` declares two independent message fields -- `INFOMSGI PIC X(45)` at L234
 * and `ERRMSGI PIC X(78)` at L240 -- and `ui/src/layout/MessageBand.tsx` names them apart so a query
 * for one cannot resolve to the other. Asking singularly also fails if a screen ever paints two.
 * @returns {Promise<HTMLElement>} The informational band.
 */
async function informationBand(): Promise<HTMLElement> {
  return screen.findByTestId(INFORMATION_BAND_TEST_ID);
}

/**
 * Reads the row-23 error band, which this screen delegates to the shell.
 * @returns {Promise<HTMLElement>} The error band.
 */
async function errorBand(): Promise<HTMLElement> {
  return screen.findByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Returns every record view the screen renders, as the design system emits them.
 *
 * Assumptions: the selector is the design system's own bordered-record class, which is how a case
 * establishes that the read-only record went through `Descriptions` rather than through a
 * hand-assembled table. AAP section 0.4.1.4 maps this route to a record view plus one input, and
 * section 0.3.2 forbids a raw `<table>` where the system supplies one.
 * @returns {readonly HTMLElement[]} The bordered record views, in document order.
 */
function recordViews(): readonly HTMLElement[] {
  return Array.from(
    document.querySelectorAll<HTMLElement>('.ant-descriptions.ant-descriptions-bordered'),
  );
}

/**
 * Reads the value cell the record view renders against one painted label.
 *
 * Assumptions: the value is found by walking FORWARD from the matching label cell to the next value
 * cell beside it, never by taking the first value cell in the row. At two columns the design system
 * emits two label-and-value pairs inside one `<tr>`, so a row-scoped search returns the LEFT pair's
 * value for a label in the right pair -- which is a locator that silently reads the wrong field
 * rather than failing.
 *
 * Refactoring Rationale: that is not hypothetical; it is what this helper did. A row-scoped
 * `querySelector` made two cases compare the wrong values -- the current-cycle-credit amount read as
 * the current balance, and the masked national identifier read as the customer identifier -- and both
 * failures pointed at the screen while the screen was correct. The walk is also what keeps the
 * pairing independent of position, which is the property AAP gap G1 requires a case to rely on:
 * reading order and grouping are preserved by the migration, cell coordinates are not.
 * @param {string} label - The painted label, from the message catalog.
 * @returns {HTMLElement} The value cell paired with that label.
 * @throws {Error} If no record view pairs a value with that label.
 */
function recordValueFor(label: string): HTMLElement {
  const wanted = collapse(label);
  const labelCells = Array.from(
    document.querySelectorAll<HTMLElement>('.ant-descriptions-item-label'),
  );

  for (const cell of labelCells) {
    if (collapsedTextOf(cell) !== wanted) {
      continue;
    }

    /*
     * WHY : Assumptions: the search advances through following SIBLINGS rather than stopping at the
     *       immediate one, because the bordered and unbordered layouts nest the pair differently and
     *       only the ORDER is common to both. Advancing until a value cell is met accommodates either
     *       without the case having to know which layout the record view chose.
     */
    let sibling: Element | null = cell.nextElementSibling;
    while (sibling !== null) {
      /*
       * WHY : Assumptions: the element type is NARROWED rather than asserted, because a cast would
       *       claim a guarantee the DOM does not give at this point and `ui/tsconfig.json` runs in
       *       strict mode precisely so that claim has to be earned. The narrowing costs one test and
       *       makes the returned value genuinely an element with a style and a text content.
       */
      if (
        sibling instanceof HTMLElement &&
        sibling.classList.contains('ant-descriptions-item-content')
      ) {
        return sibling;
      }
      sibling = sibling.nextElementSibling;
    }
  }

  throw new Error(`no record view paired a value with the label ${JSON.stringify(wanted)}`);
}

/**
 * Programs the mocked read to resolve with one composed view.
 *
 * Assumptions: every case that triggers a read programs its OWN answer, which is what removes the
 * need for a hand-written reset hook. `ui/vitest.config.ts` sets `clearMocks` and `restoreMocks`, so
 * call history and implementations do not survive a case boundary; setting the answer per case means
 * no case can inherit another's even if that guarantee were ever relaxed.
 * @param {AccountViewResponse} view - The composed view the read resolves with.
 * @returns {void} Nothing; the spy is programmed as a side effect.
 */
function readResolvingWith(view: AccountViewResponse): void {
  /*
   * WHY : Assumptions: the revision is `null`, which the contract permits. `ui/src/api/accounts.ts`
   *       records that the entity tag is absent for a read with no use for it, and this screen is
   *       read-only -- it deliberately does not retain the revision, because that value is the
   *       precondition an EDIT submits under.
   */
  vi.mocked(readAccountView).mockResolvedValue({ account: view, revision: null });
}

/**
 * Keys an entry into the filter and raises the Enter attention identifier.
 *
 * Assumptions: the submission is driven by a REAL key event rather than by calling a handler,
 * because the 3270 original was operated entirely from the keyboard, so the binding is the
 * fidelity-bearing path. `pressPfKey` derives the browser key by inverting the hook's own published
 * table and round-trips it back through `resolveAid`, so this cannot drift from what the screen
 * listens for.
 * @param {HarnessRenderResult} rendered - The render result, for its operator.
 * @param {string} entry - The value to key into the filter.
 * @returns {Promise<void>} Resolves once the entry has been keyed and Enter dispatched.
 */
async function submitFilter(rendered: HarnessRenderResult, entry: string): Promise<void> {
  if (entry.length > 0) {
    await rendered.user.type(filterControl(), entry);
  }
  await pressPfKey(rendered.user, 'ENTER');
}

/**
 * Asserts one rendered amount carries the reference's edit mask and none of its exactness.
 *
 * Purpose
 * -------
 * The mapset gives all five monetary fields `PICOUT='+ZZZ,ZZZ,ZZZ.99'` with `JUSTIFY=(RIGHT)`, and
 * the masked result is exactly the `PIC X(15)` the receiving field declares. Both halves are
 * checked here: the width the mask produces, and the digits surviving it unchanged.
 *
 * Trade-offs: the digits are recovered by removing ONLY the characters the mask introduces -- the
 * sign, the grouping commas and the leading pad -- rather than by parsing the rendered text into a
 * number and comparing. Parsing is precisely the conversion the whole money contract exists to
 * prevent: `ui/src/api/types.ts` declares `Money` as `string` so that no client routes an exact
 * decimal through an IEEE-754 double, where `1234.56` has no exact representation and a cent can be
 * lost in the round trip. So no assertion in this file applies a numeric constructor, a
 * floating-point parse, a unary plus or a fixed-point rounding call to an amount.
 * @param {HTMLElement} cell - The record view's value cell for a monetary field.
 * @param {string} wireAmount - The amount exactly as the fixture put it on the wire.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function expectEditMaskedMoney(cell: HTMLElement, wireAmount: string): void {
  const rendered = cell.textContent ?? '';

  expect(rendered).toHaveLength(MONEY_FIELD_DECLARED_WIDTH);
  expect(rendered.replace(/[+\s,]/gu, '')).toBe(wireAmount);
}

/**
 * Converts a design-system token name to the custom-property name it resolves to.
 *
 * Assumptions: the design system emits each token as a kebab-cased custom property under its own
 * prefix, so `fontFamilyCode` reaches the DOM as `--ant-font-family-code`. Deriving the name from
 * the token constant rather than writing the property out is what keeps this file free of a literal
 * font value: AAP section 0.3.2 admits no hardcoded CSS values, and a test that hardcoded the
 * resolved stack would pass while the element had been opted out of the theme.
 * @param {string} tokenName - The token name as `ui/src/theme/tokens.ts` publishes it.
 * @returns {string} The kebab-cased fragment the custom property carries.
 */
function customPropertyFragmentFor(tokenName: string): string {
  return tokenName.replace(/([a-z0-9])([A-Z])/gu, '$1-$2').toLowerCase();
}

/**
 * The filter carries its declared width and is the screen's only editable control.
 *
 * Assumptions: exactly one control is editable because exactly one `DFHMDF` in this mapset carries
 * `UNPROT`. `app/bms/COACTVW.bms` L84 declares `ACCTSID DFHMDF ATTRB=(FSET,IC,NORM,UNPROT)` and the
 * other 36 named fields are protected, so a second textbox anywhere on this screen would offer an
 * editability transaction `CAVW` does not have -- the account-UPDATE screen is where that belongs.
 * @returns {Promise<void>} Resolves once the width and the control count have been asserted.
 */
async function theFilterCarriesItsDeclaredWidthAndIsTheOnlyEditableControl(): Promise<void> {
  await renderScreen();
  await informationBand();

  expectMaxLength(filterControl(), ACCOUNT_ID_DECLARED_WIDTH);
  expect(screen.getAllByRole('textbox')).toHaveLength(1);
  /*
   * WHY : Assumptions: the control is the design system's own input and not a raw element. AAP
   *       section 0.3.2 requires a library component wherever one exists, so the class the system
   *       stamps on its input is the evidence that requirement was met -- a bare `<input>` would
   *       satisfy the role query above and carry none of the theme.
   */
  expect(filterControl()).toHaveClass('ant-input');
}

/**
 * The filter holds the one cursor position the mapset sets.
 *
 * Assumptions: `app/bms/COACTVW.bms` carries exactly ONE `IC` attribute, on `ACCTSID` at L84, so
 * exactly one control may open focused. `app/cbl/COACTVWC.cbl` L546-L552 reinforces it by moving
 * `-1` into `ACCTSIDL` in every branch of its cursor `EVALUATE`: the cursor sits on this field
 * whatever happened on the previous turn, which makes focusing it transcription rather than a choice.
 *
 * Trade-offs: focus is asserted through the active element rather than through an `autofocus`
 * attribute, because React implements the property by calling `focus()` rather than by emitting the
 * attribute -- so the attribute is absent even when the behaviour is correct, and asserting it would
 * fail against a faithful screen.
 * @returns {Promise<void>} Resolves once the cursor position has been asserted.
 */
async function theFilterHoldsTheMapsetsOnlyCursorPosition(): Promise<void> {
  await renderScreen();
  await informationBand();

  expect(filterControl()).toHaveFocus();
  expect(document.querySelectorAll('input')).toHaveLength(1);
}

/**
 * The filter accepts digits and refuses every other character.
 *
 * Assumptions: the digit-only alphabet is asserted on THIS screen specifically because this screen's
 * receiving field is declared numerically, and its sibling's is not. `app/cpy-bms/COACTVW.CPY` L60
 * declares `ACCTSIDI PIC 99999999999` -- eleven digit positions -- while
 * `app/cpy-bms/COACTUP.CPY` L60 declares the same-named field as `ACCTSIDI PIC X(11)`. That is a
 * measured per-screen difference, not a transcription slip, so the two screens are not assumed to
 * declare the field identically and the numeric picture here is what this case pins.
 *
 * Assumptions: the map states the same rule a second way. `app/bms/COACTVW.bms` L84-L90 gives the
 * field `PICIN='99999999999'` with `VALIDN=(MUSTFILL)`, which makes a non-numeric or partial entry
 * unenterable at the terminal rather than merely invalid -- so filtering on the way in is the
 * browser equivalent of a field that could not receive the character at all, and the operator never
 * reaches a refusal complaining about digits they did not type.
 * @returns {Promise<void>} Resolves once the refused characters have been asserted.
 */
async function theFilterRefusesEverythingButDigits(): Promise<void> {
  const rendered = await renderScreen();
  await informationBand();

  await rendered.user.type(filterControl(), 'A1b2-3/4 5');

  expect(filterControl()).toHaveValue('12345');
}

/**
 * The filter stops accepting characters at its declared width.
 *
 * Assumptions: the twelfth digit is DISCARDED rather than replacing the eleventh, which is the 3270
 * behaviour the width contract preserves -- a hardware field accepted its declared width and the
 * next keystroke did nothing. The width itself is the eleven fixed by the three declarations
 * recorded on {@link ACCOUNT_ID_DECLARED_WIDTH}.
 * @returns {Promise<void>} Resolves once the truncation has been asserted.
 */
async function theFilterStopsAtItsDeclaredWidth(): Promise<void> {
  const rendered = await renderScreen();
  await informationBand();

  await rendered.user.type(filterControl(), '9999999999988');

  expect(filterControl()).toHaveValue('99999999999');
  expect(String(filterControl().getAttribute('value') ?? '')).toHaveLength(
    ACCOUNT_ID_DECLARED_WIDTH,
  );
}

/**
 * A successful read renders both record blocks through the design system's record view.
 *
 * Assumptions: two bordered record views are expected and not one, because the mapset paints two
 * labelled regions -- the account block under `View Account` at row 4 and the customer block under
 * `Customer Details` at `POS=(11,32)`. AAP section 0.4.1.4 maps this route to a record view plus the
 * one account input, and section 0.3.2 fixes the record view as bordered.
 *
 * Assumptions: no raw HTML control is admitted anywhere. Every table in the document must belong to
 * a record view and every button to the key legend, which together exclude the hand-assembled table
 * and the bare `<button>` that AAP section 0.3.2 forbids where the system supplies a component.
 * @returns {Promise<void>} Resolves once the composition has been asserted.
 */
async function theRecordRendersThroughTheDesignSystemsRecordViews(): Promise<void> {
  readResolvingWith(accountView());
  const rendered = await renderScreen();
  await informationBand();

  await submitFilter(rendered, ACCOUNT_ID);
  await waitFor(assertAccountBlockRendered);

  expect(recordViews()).toHaveLength(2);
  expect(recordValueFor(ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.CUSTOMER_ID)).toHaveTextContent(
    '000000011',
  );

  /*
   * WHY : Assumptions: the two exclusions are written as containment tests over every matching
   *       element rather than as counts, because a count would have to be revised whenever a block
   *       gained a field -- and would then be revised without anyone re-checking what the new
   *       element actually is.
   */
  for (const table of Array.from(document.querySelectorAll('table'))) {
    expect(table.closest('.ant-descriptions')).not.toBeNull();
  }
  for (const button of Array.from(document.querySelectorAll('button'))) {
    expect(button.closest('.ant-btn')).not.toBeNull();
  }
}

/**
 * Waits until the account block has been painted.
 *
 * Refactoring Rationale: the wait predicate is a named function rather than an inline arrow, for the
 * reason recorded on {@link legendLabels} -- `ui/eslint.config.js` would require its own JSDoc block
 * on the arrow, and several cases wait on this same condition.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function assertAccountBlockRendered(): void {
  expect(recordValueFor(ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.ACTIVE_STATUS)).toHaveTextContent('Y');
}

/**
 * The five amounts render as fixed-pitch strings at their declared width, with no coercion.
 *
 * Assumptions: all five of the baseline's `PICOUT='+ZZZ,ZZZ,ZZZ.99'` money fields are in this
 * mapset, which `ui/src/theme/tokens.ts` records beside the fixed-pitch token, and each is
 * `PIC X(15)` -- `ACRDLIMI` at `app/cpy-bms/COACTVW.CPY` L78, `ACSHLIMI` at L90, `ACURBALI` at L102,
 * `ACRCYCRI` at L108 and `ACRCYDBI` at L120. All five are checked rather than a sample, because the
 * mask is applied at one render site and a field that stopped being marked monetary would lose both
 * the mask and the font while every other field still passed.
 *
 * Assumptions: the font arrives as a CUSTOM-PROPERTY reference and not as a resolved font stack.
 * `ui/src/theme/tokens.ts` maps the fixed-pitch role to `fontFamilyCode`, and the screen reads the
 * theme's variable reference rather than the resolved value -- reading the resolved value would copy
 * today's stack into the element's inline style and opt it out of the theme silently, which is a
 * regression no visual check in this suite would catch.
 * @returns {Promise<void>} Resolves once every amount has been asserted.
 */
async function theFiveAmountsRenderAsFixedPitchStringsAtTheirDeclaredWidth(): Promise<void> {
  const view = accountView();
  readResolvingWith(view);
  const rendered = await renderScreen();
  await informationBand();

  await submitFilter(rendered, ACCOUNT_ID);
  await waitFor(assertAccountBlockRendered);

  const amounts: readonly (readonly [string, string])[] = [
    [ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CREDIT_LIMIT, view.account.creditLimit],
    [ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CASH_CREDIT_LIMIT, view.account.cashCreditLimit],
    [ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CURRENT_BALANCE, view.account.currentBalance],
    [ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CURRENT_CYCLE_CREDIT, view.account.currentCycleCredit],
    [ACCOUNT_VIEW_ACCOUNT_FIELD_LABELS.CURRENT_CYCLE_DEBIT, view.account.currentCycleDebit],
  ];

  const fragment = customPropertyFragmentFor(TYPOGRAPHY_TOKENS.fixedPitchData);

  for (const [label, wireAmount] of amounts) {
    expect(typeof wireAmount).toBe('string');

    const cell = recordValueFor(label);
    expectEditMaskedMoney(cell, wireAmount);

    const styled = cell.querySelector<HTMLElement>('[style*="font-family"]');
    expect(styled).not.toBeNull();
    expect(styled?.getAttribute('style') ?? '').toContain(fragment);
  }
}

/**
 * One sentence the program declares, paired with the declaration it is transcribed from.
 *
 * Alternatives Considered: asserting each sentence against a literal retyped into this file. It is
 * rejected because it asserts nothing: a paraphrase written into the screen and the same paraphrase
 * written into the test agree with each other, the case passes, and the fidelity Transformation
 * Rule T8 exists to protect is gone. What is checkable instead is the catalog's own PROVENANCE --
 * that it holds exactly the declared set, each entry citing the line and the fixed-width field it
 * came from -- while the behaviour cases assert that the screen renders the catalog's text. Neither
 * half restates a sentence.
 */
interface DeclaredSentence {
  /** The catalog entry, carrying the text and its recorded provenance. */
  readonly entry: (typeof VIEW_MESSAGES)[keyof typeof VIEW_MESSAGES];
  /** Line of `app/cbl/COACTVWC.cbl` declaring the value. */
  readonly line: number;
  /** Data name of the field the value is declared under. */
  readonly field: string;
  /** `PICTURE` width of that field, in characters. */
  readonly declaredWidth: number;
}

/**
 * The twelve sentences `app/cbl/COACTVWC.cbl` declares, each paired with its declaration.
 *
 * Assumptions: two fields declare all twelve and fix the two widths -- `WS-INFO-MSG PIC X(40)` at
 * L110 carries the two informational values, and `WS-RETURN-MSG PIC X(75)` at L117 carries the other
 * ten. The line numbers are the `88`-level VALUE lines, which is where the bytes are.
 *
 * Assumptions: L126 and L128 are listed SEPARATELY and carry the same text deliberately.
 * `SEARCHED-ACCT-ZEROES` and `SEARCHED-ACCT-NOT-NUMERIC` are two distinct condition names declared
 * over one identical literal, which was confirmed by comparing the two source lines directly -- so
 * neither is a typo of the other and a maintainer must not merge them.
 */
const DECLARED_SENTENCES: readonly DeclaredSentence[] = [
  { entry: VIEW_MESSAGES.WS_PROMPT_FOR_INPUT, line: 114, field: 'WS-INFO-MSG', declaredWidth: 40 },
  { entry: VIEW_MESSAGES.WS_INFORM_OUTPUT, line: 116, field: 'WS-INFO-MSG', declaredWidth: 40 },
  { entry: VIEW_MESSAGES.WS_EXIT_MESSAGE, line: 120, field: 'WS-RETURN-MSG', declaredWidth: 75 },
  { entry: VIEW_MESSAGES.WS_PROMPT_FOR_ACCT, line: 122, field: 'WS-RETURN-MSG', declaredWidth: 75 },
  {
    entry: VIEW_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED,
    line: 124,
    field: 'WS-RETURN-MSG',
    declaredWidth: 75,
  },
  {
    entry: VIEW_MESSAGES.SEARCHED_ACCT_ZEROES,
    line: 126,
    field: 'WS-RETURN-MSG',
    declaredWidth: 75,
  },
  {
    entry: VIEW_MESSAGES.SEARCHED_ACCT_NOT_NUMERIC,
    line: 128,
    field: 'WS-RETURN-MSG',
    declaredWidth: 75,
  },
  {
    entry: VIEW_MESSAGES.DID_NOT_FIND_ACCT_IN_CARDXREF,
    line: 130,
    field: 'WS-RETURN-MSG',
    declaredWidth: 75,
  },
  {
    entry: VIEW_MESSAGES.DID_NOT_FIND_ACCT_IN_ACCTDAT,
    line: 132,
    field: 'WS-RETURN-MSG',
    declaredWidth: 75,
  },
  {
    entry: VIEW_MESSAGES.DID_NOT_FIND_CUST_IN_CUSTDAT,
    line: 134,
    field: 'WS-RETURN-MSG',
    declaredWidth: 75,
  },
  { entry: VIEW_MESSAGES.XREF_READ_ERROR, line: 136, field: 'WS-RETURN-MSG', declaredWidth: 75 },
  { entry: VIEW_MESSAGES.CODING_TO_BE_DONE, line: 138, field: 'WS-RETURN-MSG', declaredWidth: 75 },
];

/**
 * The catalog holds exactly the twelve declared sentences, each citing its own declaration.
 *
 * Assumptions: the count is asserted as well as the contents, so a thirteenth entry appearing under
 * this program -- a sentence invented for the target, which is the failure this guards -- fails here
 * rather than reaching a screen.
 *
 * Trade-offs: this case renders nothing and is therefore synchronous, unlike its neighbours. What it
 * establishes is a property of the CATALOG rather than of the screen, so mounting a tree would add
 * setup cost and a second reason for the case to fail without strengthening what it proves.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theCatalogHoldsTheTwelveDeclaredSentences(): void {
  expect(Object.keys(VIEW_MESSAGES)).toHaveLength(DECLARED_SENTENCES.length);
  /*
   * WHY : Assumptions: twelve is the count of `88`-level VALUE clauses this program declares over its
   *       two message fields, taken from `app/cbl/COACTVWC.cbl` L114 through L138 inclusive -- two
   *       under `WS-INFO-MSG` and ten under `WS-RETURN-MSG`. It is asserted as a literal beside the
   *       table's own length so that deleting a row from {@link DECLARED_SENTENCES} cannot make the
   *       comparison above pass by agreeing with a shortened table.
   */
  expect(DECLARED_SENTENCES).toHaveLength(12);

  for (const declared of DECLARED_SENTENCES) {
    expect(declared.entry.line).toBe(declared.line);
    expect(declared.entry.field).toBe(declared.field);
    expect(declared.entry.declaredWidth).toBe(declared.declaredWidth);
    expect(declared.entry.text.length).toBeLessThanOrEqual(declared.declaredWidth);
  }
}

/**
 * The exit sentence keeps the trailing pad its declaration carries.
 *
 * Assumptions: the literal at `app/cbl/COACTVWC.cbl` L120 is thirty-four characters -- a twenty-
 * character body followed by fourteen spaces -- which was measured from the source line rather than
 * counted by eye. The pad is part of the value under Transformation Rule T8, so a catalog entry that
 * trimmed it would be a different string, and the two lengths below are what make that detectable.
 *
 * Assumptions: the sentence is nevertheless not rendered on exit by this screen, and that is
 * correct rather than a gap. `WS-EXIT-MESSAGE` is an `88`-level value of `WS-RETURN-MSG` and is
 * `SET` nowhere in the program -- its PF3 arm at L323-L345 moves navigation fields and transfers
 * control without emitting a message -- so the catalog holds the declared value while the screen
 * shows nothing, which is parity. The entry is checked here for its bytes, not for its use.
 *
 * Trade-offs: synchronous for the reason recorded on {@link theCatalogHoldsTheTwelveDeclaredSentences}
 * -- the pad is a property of the catalogued value, so no tree needs mounting to observe it.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theExitSentenceKeepsItsTrailingPad(): void {
  const exitSentence = VIEW_MESSAGES.WS_EXIT_MESSAGE.text;

  expect(exitSentence).toHaveLength(34);
  expect(exitSentence.trimEnd()).toHaveLength(20);
  expect(exitSentence).not.toBe(exitSentence.trimEnd());
}

/**
 * The validation-pass sentence keeps its four-dot run, and the two zero-or-non-numeric
 * condition names keep their shared text.
 *
 * Assumptions: `app/cbl/COACTVWC.cbl` L138 declares `Looks Good.... so far` with a run of exactly
 * FOUR full stops -- not three, and not a single ellipsis character. A run of a different length or
 * a typographic ellipsis would render as different bytes, so the run is measured rather than matched
 * loosely.
 *
 * Assumptions: the L126 and L128 texts are asserted EQUAL to each other rather than each compared
 * to a literal, which pins the duplication itself as the contract. Two condition names over one
 * literal is what the source declares, and asserting the equality is what stops a maintainer
 * "correcting" one of them.
 *
 * Trade-offs: synchronous for the reason recorded on {@link theCatalogHoldsTheTwelveDeclaredSentences}
 * -- the punctuation and the duplication are properties of the catalogued values themselves.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function theDeclaredSentencesKeepTheirExactPunctuationAndDuplication(): void {
  const dotRun = /\.+/u.exec(VIEW_MESSAGES.CODING_TO_BE_DONE.text);

  expect(dotRun).not.toBeNull();
  expect(dotRun?.[0]).toHaveLength(4);

  expect(VIEW_MESSAGES.SEARCHED_ACCT_ZEROES.text).toBe(
    VIEW_MESSAGES.SEARCHED_ACCT_NOT_NUMERIC.text,
  );

  /*
   * WHY : Assumptions: the refusal the program MOVES is a third string, distinct from both of the
   *       identical condition values above. `app/cbl/COACTVWC.cbl` L672 moves a literal with a
   *       double space after "must" and "non-zero" hyphenated, while L126 and L128 declare a single
   *       space and no hyphen. Asserting the inequality is what keeps the catalog from collapsing
   *       three declarations into one and silently changing what the screen displays.
   */
  expect(ACCOUNT_FILTER_REFUSAL).not.toBe(VIEW_MESSAGES.SEARCHED_ACCT_ZEROES.text);
}

/**
 * A malformed filter is refused with the moved literal, without any read being issued.
 *
 * Assumptions: the sentence asserted is the L672 literal and not either declared condition value,
 * for the reason recorded on {@link ACCOUNT_FILTER_REFUSAL}. It is asserted through
 * `expectVerbatimMessage`, which compares WITHOUT collapsing whitespace -- so the double space after
 * "must" has to survive into the DOM, and a screen that normalised it would fail here.
 *
 * Assumptions: the refusal carries the error STATE and no help text. `app/cbl/COACTVWC.cbl` L532
 * moves the sentence into the row-23 field and `app/cpy/CSSETATY.cpy` L17-L27 gives the field itself
 * only a colour, never per-field text -- so printing the sentence a second time under the control
 * would have one refusal read in two places.
 * @returns {Promise<void>} Resolves once the local refusal has been asserted.
 */
async function aMalformedFilterIsRefusedWithTheMovedLiteral(): Promise<void> {
  const rendered = await renderScreen();
  await informationBand();

  await submitFilter(rendered, '123');

  await waitFor(assertFilterRefused);
  expectVerbatimMessage(ACCOUNT_FILTER_REFUSAL);
  expect(readAccountView).not.toHaveBeenCalled();
  expect(filterControl()).not.toHaveAttribute('aria-describedby');
  expect(screen.queryByText(collapse(VIEW_MESSAGES.SEARCHED_ACCT_ZEROES.text))).toBeNull();
}

/**
 * Waits until the filter has been marked as refused.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function assertFilterRefused(): void {
  expect(filterControl()).toHaveAttribute('aria-invalid', 'true');
}

/**
 * A blank filter is refused with the cross-field sentence and marked with the blank marker.
 *
 * Assumptions: the sentence is `No input received` and not `Account number not provided`, and the
 * order of two statements in the source decides which. The blank branch at
 * `app/cbl/COACTVWC.cbl` L653-L661 sets `WS-PROMPT-FOR-ACCT` guarded by `IF WS-RETURN-MSG-OFF`, and
 * the cross-field edit at L640-L642 then replaces it UNCONDITIONALLY with
 * `NO-SEARCH-CRITERIA-RECEIVED`. The first sentence is therefore set and immediately overwritten,
 * so the second is what an operator sees -- the one place in this program where a later write wins.
 *
 * Assumptions: the marker appears for the BLANK refusal only. `app/cbl/COACTVWC.cbl` L561-L565
 * writes `'*'` on the blank path alone and `app/cpy/CSSETATY.cpy` L23-L25 makes the same
 * distinction, so a malformed eleven-digit entry is coloured and unmarked.
 *
 * Assumptions: the marker is an adornment beside the control and is NOT the control's value. The
 * value of an input is the field's data, so writing a decoration there would make the marker the
 * account number as far as an assistive technology, an autofill and a copy of the field are
 * concerned -- which the reference never does, a 3270 field having separate data and attribute
 * subfields.
 * @returns {Promise<void>} Resolves once the blank refusal and its marker have been asserted.
 */
async function aBlankFilterIsRefusedAndCarriesTheBlankMarker(): Promise<void> {
  const rendered = await renderScreen();
  await informationBand();

  await submitFilter(rendered, '');

  await waitFor(assertFilterRefused);
  expect(await errorBand()).toHaveTextContent(
    collapse(VIEW_MESSAGES.NO_SEARCH_CRITERIA_RECEIVED.text),
  );
  expect(readAccountView).not.toHaveBeenCalled();
  expect(filterControl()).toHaveValue('');

  const marker = document.querySelector(`[aria-hidden="true"]`);
  expect(marker).not.toBeNull();
  expect(marker?.textContent).toBe(FIELD_ERROR_TOKENS.blankMarker);
}

/**
 * A field error the service addressed to the filter renders as help text against the control.
 *
 * Assumptions: BOTH halves are asserted, because the screen performs the mapping itself.
 * `ui/src/layout/MessageBand.tsx` is presentational and takes a sentence rather than a problem
 * document, so the screen routes the document's screen-level message to the band and each
 * `fieldErrors` entry to the control its `field` names. A case asserting only one half would pass
 * while the other channel was dropped.
 *
 * Refactoring Rationale: no re-entry flag is asserted, and none exists to assert.
 * `app/cpy/CSSETATY.cpy` L18-L27 and `app/cbl/COACTVWC.cbl` L561-L565 both gate the highlight on
 * `CDEMO-PGM-REENTER` -- declared at `app/cpy/COCOM01Y.cpy` L29-L31 -- because the reference had to
 * remember that a turn was a second turn to know an error was worth showing. AAP section 0.7.1
 * establishes that the discriminator disappears entirely in the target: a stateless handler answers
 * with the refusal or it does not, so the highlight is driven purely by the response body.
 * @returns {Promise<void>} Resolves once both channels have been asserted.
 */
async function aServiceFieldErrorRendersAgainstTheControl(): Promise<void> {
  const screenSentence = VIEW_MESSAGES.DID_NOT_FIND_ACCT_IN_ACCTDAT.text;
  vi.mocked(readAccountView).mockRejectedValue(
    apiError({
      message: screenSentence,
      fieldErrors: [fieldError(ACCOUNT_ID_RESPONSE_FIELD, ACCOUNT_FILTER_REFUSAL)],
    }),
  );

  const rendered = await renderScreen();
  await informationBand();

  await submitFilter(rendered, ACCOUNT_ID);

  await waitFor(assertFilterRefused);
  expect(await errorBand()).toHaveTextContent(collapse(screenSentence));

  /*
   * WHY : Assumptions: the association is asserted through the identifier the control POINTS at
   *       rather than by the sentence merely being present somewhere. `ui/src/layout/fieldHelp.tsx`
   *       derives the refusal element's identifier from the control's own by suffixing `-error`, so
   *       resolving that identifier is what proves an assistive technology would announce the
   *       refusal -- text rendered under the control while nothing referenced it reached the screen
   *       and not the operator.
   */
  const describedBy = `${ACCOUNT_ID_CONTROL_ID}-error`;
  expect(filterControl()).toHaveAttribute('aria-describedby', describedBy);
  expect(document.getElementById(describedBy)?.textContent).toBe(ACCOUNT_FILTER_REFUSAL);
}

/**
 * The selected account reaches the service as an explicit request value, not as session state.
 *
 * Assumptions: the reference carries the selection in storage the client hands back --
 * `CDEMO-ACCT-ID PIC 9(11)`, `CDEMO-CARD-NUM PIC 9(16)` and `CDEMO-CUST-ID PIC 9(09)` at
 * `app/cpy/COCOM01Y.cpy` L33-L41 -- and the target carries none of it. AAP section 0.7.1 replaces
 * that struct with an explicit per-request value, which is what makes every request self-describing
 * and therefore independently authorizable: the client can no longer assert a selection the server
 * merely echoed back.
 *
 * Refactoring Rationale: the value travels in the request BODY rather than in the request line, and
 * the route accordingly declares no parameter. `ui/src/api/accounts.ts` posts `{ accountId }` to the
 * static path `/api/v1/accounts/view`, and `ui/src/router.tsx` declares `/account/view` with no
 * segment of its own. The reason is disclosure rather than convenience: a value in a request line is
 * written to whatever access log the edge keeps and that log outlives the request, and a browser
 * query member is the same disclosure with a longer tail -- session history, the `Referer` on any
 * onward navigation, and a copied or bookmarked URL carrying a real customer's account number.
 * Asserting the absence of a parameter is therefore asserting the property, not conceding it.
 * @returns {Promise<void>} Resolves once the request value has been asserted.
 */
async function theSelectedAccountTravelsAsAnExplicitRequestValue(): Promise<void> {
  readResolvingWith(accountView());
  const rendered = await renderScreen();
  await informationBand();

  await submitFilter(rendered, ACCOUNT_ID);
  await waitFor(assertAccountBlockRendered);

  expect(readAccountView).toHaveBeenCalledTimes(1);
  expect(readAccountView).toHaveBeenCalledWith(ACCOUNT_ID);
  expect(ACCOUNT_VIEW_PATH).not.toContain(':');
}

/**
 * The legend paints the one descriptor the mapset carries and never advertises Enter.
 *
 * Assumptions: the label is byte-exact including its surrounding blanks.
 * `app/bms/COACTVW.bms` L369-L373 declares the row-24 field at `POS=(24,1)` with
 * `INITIAL='  F3=Exit '` -- two leading spaces and one trailing space -- and
 * `ui/src/layout/PfKeyBar.tsx` renders the descriptor with no trimming, so the raw text is compared
 * before the collapsed comparison rather than instead of it.
 *
 * Assumptions: Enter is absent from the legend even though the program admits it.
 * `app/cbl/COACTVWC.cbl` L307-L308 accepts `CCARD-AID-ENTER` and `CCARD-AID-PFK03`, yet the mapset
 * paints only the PF3 descriptor -- so a legend advertising Enter would paint a key the terminal did
 * not, and it would do so plausibly, which is exactly why the absence is worth an assertion.
 * @returns {Promise<void>} Resolves once the legend has been asserted.
 */
async function theLegendPaintsOnlyTheExitDescriptor(): Promise<void> {
  await renderScreen();
  await informationBand();

  const controls = Array.from(legendRegion().querySelectorAll('button'));

  expect(controls).toHaveLength(1);
  expect(controls[0]?.textContent).toBe(ACCOUNT_VIEW_KEY_LABELS.PFK03);
  expect(legendLabels()).toEqual([collapse(ACCOUNT_VIEW_KEY_LABELS.PFK03)]);

  /*
   * WHY : Assumptions: the legend is asserted to live inside the shell's row-24 landmark rather than
   *       merely to exist. The mapset puts this field on the last line of the screen and the shell
   *       owns that row for every screen, so a legend rendered in the body would be the same controls
   *       in the wrong region -- which a document-wide query could not tell apart.
   */
  expect(shellLandmark('keyLegend')).toContainElement(legendRegion());
}

/**
 * Enter and the exit key are the only bound identifiers, and the exit key dispatches identically
 * from the keyboard and from its legend control.
 *
 * Alternatives Considered: driving the keys only by clicking their legend controls, which is simpler
 * because it needs no key-event plumbing. It is rejected because it would invert what this contract
 * is: the 3270 original had no pointer, so the KEYBOARD binding is the fidelity-bearing path, and a
 * click-only case stays green while every keyboard binding in the application is broken. Both paths
 * are therefore driven, and the equivalence between them is the assertion.
 *
 * Assumptions: leaving the screen is observed as the screen being unmounted rather than as a
 * destination being painted. The route mounted here is this screen's own, so a transition away from
 * it matches nothing and the filter disappears -- which is the dispatch reaching the router. Which
 * destination the exit key chooses is settled by `app/cbl/COACTVWC.cbl` L328-L339 and is asserted
 * where the screen-to-menu navigation is owned, so it is not restated here.
 *
 * Assumptions: the router reports "No routes matched location" on the console while this case runs,
 * and that notice is the EVIDENCE rather than a fault -- it is the router confirming it received a
 * transition to a destination this deliberately narrow tree does not mount. A maintainer who
 * silenced it by mounting the destination would be replacing the observation this case makes with the
 * one the navigation cases already make.
 * @returns {Promise<void>} Resolves once both dispatch paths have been asserted.
 */
async function theExitKeyDispatchesFromKeyboardAndLegendAlike(): Promise<void> {
  const byKeyboard = await renderScreen();
  await informationBand();
  await pressPfKey(byKeyboard.user, 'PFK03');
  await waitFor(assertScreenLeft);
  byKeyboard.unmount();

  const byLegend = await renderScreen();
  await informationBand();
  await pressLegendControl(byLegend, ACCOUNT_VIEW_KEY_LABELS.PFK03);
  await waitFor(assertScreenLeft);
}

/**
 * Waits until the screen has been left, which unmounts its only control.
 * @returns {void} Nothing; the assertion carries the outcome.
 */
function assertScreenLeft(): void {
  expect(screen.queryByRole('textbox')).toBeNull();
}

/**
 * An unmapped key is coerced into Enter and emits no invalid-key sentence.
 *
 * Assumptions: coercion is exactly what the reference does. `app/cbl/COACTVWC.cbl` L306-L314 sets
 * `PFK-INVALID`, promotes only ENTER and PF03 to `PFK-VALID`, and then, `IF PFK-INVALID`, performs
 * `SET CCARD-AID-ENTER TO TRUE` -- so an unmapped key is re-labelled rather than reported, and the
 * read below is the coerced submission.
 *
 * Assumptions: no invalid-key sentence is asserted PRESENT because this program emits none, which
 * was established by search rather than assumed: `CCDA-MSG-INVALID-KEY` appears nowhere in
 * `app/cbl/COACTVWC.cbl`, and among the interactive screen programs exactly six never move it --
 * `COACTVWC` with `COACTUPC`, `COCRDLIC`, `COCRDSLC`, `COCRDUPC` and `COTRTLIC`. Asserting the
 * sentence here would be a fabrication, so its ABSENCE is asserted instead, which is what stops a
 * maintainer adding the "missing" message.
 *
 * Assumptions: the PF13-to-PF24 folding and the deliberately unbound `CLEAR`, `PA1` and `PA2`
 * identifiers are NOT exercised here. They are behaviours of the shared hook rather than of this
 * screen, and they are owned by the hook's own tests; restating them per screen would put one
 * contract in twenty-one places.
 * @returns {Promise<void>} Resolves once the coercion and the absence have been asserted.
 */
async function anUnmappedKeyIsCoercedIntoEnter(): Promise<void> {
  readResolvingWith(accountView());
  const rendered = await renderScreen();
  await informationBand();

  await rendered.user.type(filterControl(), ACCOUNT_ID);
  await pressPfKey(rendered.user, 'PFK04');

  await waitFor(assertAccountBlockRendered);
  expect(readAccountView).toHaveBeenCalledWith(ACCOUNT_ID);
  expect(screen.queryByText(collapse(INVALID_KEY_PRESSED))).toBeNull();
}

/**
 * The two protected identifiers render only in the masked form the service sends.
 *
 * Assumptions: this migration's data-exposure narrowing stores the national identifier and the
 * government-issued reference encrypted and returns them masked, so the screen must render the
 * masked value UNCHANGED and must not reformat it. The reference composes the dashed form itself --
 * `app/cbl/COACTVWC.cbl` L496-L504 does `STRING CUST-SSN(1:3) '-' CUST-SSN(4:2) '-' CUST-SSN(6:4)`
 * over `CUST-SSN PIC 9(09)` -- because it holds the digits; re-running that substring composition
 * over a mask would slice the mask into groups and present the result as though it were a number.
 *
 * Assumptions: the unmasked shapes are asserted ABSENT from the two cells rather than from the whole
 * document, and that scoping is deliberate: the customer identifier is itself nine digits, so a
 * document-wide search for a nine-digit run would match a value that is legitimately rendered.
 *
 * Assumptions: no card verification value can be rendered because none is representable.
 * `ui/src/api/types.ts` declares no such member on either record, so the contract excludes it at the
 * type level -- a stronger guarantee than any assertion over the DOM, which could only check the
 * shapes it thought to look for.
 * @returns {Promise<void>} Resolves once both masked values have been asserted.
 */
async function theProtectedIdentifiersRenderOnlyMasked(): Promise<void> {
  const view = accountView();
  readResolvingWith(view);
  const rendered = await renderScreen();
  await informationBand();

  await submitFilter(rendered, ACCOUNT_ID);
  await waitFor(assertAccountBlockRendered);

  const ssnCell = recordValueFor(ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.SSN);
  const governmentIdCell = recordValueFor(ACCOUNT_VIEW_CUSTOMER_FIELD_LABELS.GOVERNMENT_ISSUED_ID);

  expect(ssnCell.textContent).toBe(view.customer?.ssnMasked);
  expect(governmentIdCell.textContent).toBe(view.customer?.governmentIssuedIdMasked);

  /*
   * WHY : Assumptions: an unmasked national identifier is nine digits with optional group
   *       separators, which is the shape `CUST-SSN PIC 9(09)` and the reference's own dashed
   *       composition produce, and an unmasked government reference is digits alone. Matching those
   *       shapes is how a value that had lost its mask is recognised without a plaintext identifier
   *       being written into this file.
   */
  expect(ssnCell.textContent ?? '').not.toMatch(/^\d{3}-?\d{2}-?\d{4}$/u);
  expect(governmentIdCell.textContent ?? '').not.toMatch(/^\d+$/u);
}

/**
 * Registers the account-view cases.
 *
 * Assumptions: no reset hook is registered. `ui/vitest.config.ts` sets `clearMocks` and
 * `restoreMocks`, so call history and implementations are cleared between cases by the runner, and
 * every case that triggers a read programs its own answer -- so a hand-written hook would duplicate a
 * guarantee the configuration already provides.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function accountViewCases(): void {
  it(
    'bounds the filter to its declared width as the only editable control',
    theFilterCarriesItsDeclaredWidthAndIsTheOnlyEditableControl,
  );
  it("holds the mapset's only cursor position", theFilterHoldsTheMapsetsOnlyCursorPosition);
  it('refuses every filter character but digits', theFilterRefusesEverythingButDigits);
  it('stops accepting filter characters at the declared width', theFilterStopsAtItsDeclaredWidth);
  it(
    "renders both record blocks through the design system's record views",
    theRecordRendersThroughTheDesignSystemsRecordViews,
  );
  it(
    'renders the five amounts as fixed-pitch strings at their declared width',
    theFiveAmountsRenderAsFixedPitchStringsAtTheirDeclaredWidth,
  );
  it(
    'holds the twelve declared sentences in the catalog',
    theCatalogHoldsTheTwelveDeclaredSentences,
  );
  it('keeps the trailing pad on the exit sentence', theExitSentenceKeepsItsTrailingPad);
  it(
    'keeps the declared punctuation and the deliberate duplication',
    theDeclaredSentencesKeepTheirExactPunctuationAndDuplication,
  );
  it(
    'refuses a malformed filter with the moved literal',
    aMalformedFilterIsRefusedWithTheMovedLiteral,
  );
  it('marks a blank filter with the blank marker', aBlankFilterIsRefusedAndCarriesTheBlankMarker);
  it(
    'renders a service field error against the control',
    aServiceFieldErrorRendersAgainstTheControl,
  );
  it(
    'sends the selected account as an explicit request value',
    theSelectedAccountTravelsAsAnExplicitRequestValue,
  );
  it('paints only the exit descriptor in the legend', theLegendPaintsOnlyTheExitDescriptor);
  it(
    'dispatches the exit key from the keyboard and its legend control alike',
    theExitKeyDispatchesFromKeyboardAndLegendAlike,
  );
  it('coerces an unmapped key into Enter', anUnmappedKeyIsCoercedIntoEnter);
  it('renders the protected identifiers masked only', theProtectedIdentifiersRenderOnlyMasked);
}

describe('the account view screen', accountViewCases);
