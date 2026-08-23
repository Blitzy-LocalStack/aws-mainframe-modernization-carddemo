/**
 * @file Per-screen contract tests for the sign-on screen, driven through the shared harness in
 * `ui/src/test/setup.ts`.
 *
 * Purpose
 * -------
 * Pin the contracts the sign-on screen carries across from BMS mapset `app/bms/COSGN00.bms` (map
 * `COSGN0A`) and program `app/cbl/COSGN00C.cbl`, which `ui/src/router.tsx` mounts at `/signon`. Six
 * groups of contract are pinned, each with its verified source position:
 *
 * 1. Field constraints — the eight-character identifier width (`02 USERIDI PIC X(8).` at
 *    `app/cpy-bms/COSGN00.CPY` L72, `LENGTH=8` at `app/bms/COSGN00.bms` L156), the single initial
 *    cursor (`ATTRB=(FSET,IC,NORM,UNPROT)` at `app/bms/COSGN00.bms` L156, corroborated by
 *    `MOVE -1 TO USERIDL` at `app/cbl/COSGN00C.cbl` L82) and the non-display credential field
 *    (`ATTRB=(DRK,FSET,UNPROT)` at `app/bms/COSGN00.bms` L175).
 * 2. Message text — the five sentences at `app/cbl/COSGN00C.cbl` L120, L125, L242, L249 and L254,
 *    the `PIC X(50)` farewell at `app/cpy/CSMSG01Y.cpy` L18-L19 and the `PIC X(50)` invalid-key
 *    sentence at L20-L21, every one of them asserted through `ui/src/messages/messages.ts` and never
 *    through a string retyped here.
 * 3. Cursor restoration — `MOVE -1 TO PASSWDL` at `app/cbl/COSGN00C.cbl` L244 and
 *    `MOVE -1 TO USERIDL` at L250 and L255.
 * 4. Attention keys — the two arms `EVALUATE EIBAID` takes at `app/cbl/COSGN00C.cbl` L85-L95 and the
 *    row-24 legend `'ENTER=Sign-on  F3=Exit'` at `app/bms/COSGN00.bms` L201-L205.
 * 5. The onward branch — `IF CDEMO-USRTYP-ADMIN` at `app/cbl/COSGN00C.cbl` L230 transferring to
 *    `COADM01C` at L232 or `COMEN01C` at L237, re-expressed as a route change decided by the signed
 *    group claim.
 * 6. The credential's disposition — `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L21 and the
 *    direct comparison at `app/cbl/COSGN00C.cbl` L223, neither of which is carried forward.
 *
 * Parameters
 * ----------
 * A test module takes no parameters. What stands in for them is the fixture surface every case is
 * arranged from: the two stubbed operations of `ui/src/api/auth.ts` (`signOn` and
 * `answerSignOnChallenge`), the token sets {@link tokensForGroups} builds, and the refusals
 * {@link refusalWith} builds from the shared `apiError` and `fieldError` builders.
 *
 * Returns
 * -------
 * Nothing. Each registered case either passes or fails; the module exports no value, which is why it
 * carries no export statement at all.
 *
 * Exceptions or errors
 * --------------------
 * Every local helper that cannot find what it was asked for throws rather than returning a widened
 * type, so a fixture that stops matching the screen fails at the helper with a sentence naming what
 * was missing instead of at an assertion several lines later. The three that do so are
 * {@link elementOrThrow}, {@link cardFrame} and {@link routeForProgram}.
 *
 * Why this file carries weight rather than being a formality
 * ---------------------------------------------------------
 * `tests/README.md` §1.1 records that the online `CO*` CICS programs cannot be run end to end without
 * a CICS runtime, which the runner does not have, so only their extractable field-validation logic is
 * unit-tested. The consequence is specific and worth stating plainly: the three-layer COBOL suite is
 * the functional-parity oracle for the BATCH programs only. There is no golden master for `COSGN00C`
 * and no COBOL test to compare against, so nothing outside this tree can catch a sign-on behaviour
 * that drifts from the reference. Every citation above is therefore load-bearing — it is the only
 * place the reference position and the migrated behaviour are held together.
 *
 * Documentation obligation
 * ------------------------
 * Two sources impose the same obligation on this file and they agree, so this extends an established
 * house convention rather than introducing one. The project's single user-specified rule, Rule 1
 * "Explainability", requires a docstring stating purpose, parameters, return values and exceptions on
 * every function and module entry point, plus inline comments that justify each non-obvious decision
 * under one of four named categories. `tests/README.md` §12 imposes the identical obligation on
 * "every new test, fixture builder, helper, mock, and runner routine" and calls it a hard review
 * gate. `docs/CODE_DOCUMENTATION_STANDARD.md` is the written form both are discharged through, and it
 * fixes the one permitted spelling of a rationale label — plural, unparenthesised, colon retained, no
 * emphasis markup — which `config/rule1/rule1_gate.py` then decides mechanically.
 *
 * Relationship to the co-located suite
 * -----------------------------------
 * Alternatives Considered: folding these cases into `ui/src/screens/signon/signon.test.tsx`, which
 * already exercises this screen. Rejected because the two files assert along different axes and the
 * difference is the point. That file composes its own provider stack, its own router and its own
 * refusal adapters, so it verifies the screen against a tree it builds itself. This file drives the
 * screen through the SHARED harness the architecture places at `ui/src/test/**` — the same
 * `renderInAppShell`, `pressPfKey`, `expectMaxLength` and `expectVerbatimMessage` every other
 * per-screen file in this tree uses — so a change to that harness is caught here rather than only in
 * whichever screen happens to notice. It also pins contracts the co-located file does not: the
 * short-circuit with BOTH fields blank, the negative on the forty-character `CCDA` farewell, the
 * declared widths the catalog records, keyboard-and-legend parity for both bound keys, the absence of
 * any third binding, the credential's non-persistence, and the fact that authority has no
 * client-settable source.
 */

import { getDefaultNormalizer, renderHook, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ReactElement } from 'react';
import type { UserEvent } from '@testing-library/user-event';
import { useLocation } from 'react-router';

import { ApiRequestError } from '../api/client';
import type * as AuthClient from '../api/auth';
import type { FieldError, SignOnTokens } from '../api/types';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import {
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  PROGRAM_MESSAGES,
  PROGRAM_MESSAGE_SOURCES,
  SCREEN_TITLES,
  SIGN_ON_SUBMIT_LABEL,
  THANK_YOU_CARDDEMO,
  THANK_YOU_CCDA,
} from '../messages/messages';
import { FIELD_ERROR_TOKENS } from '../theme/tokens';
import {
  apiError,
  expectMaxLength,
  expectVerbatimMessage,
  fieldError,
  pressPfKey,
  renderInAppShell,
  resetSessionAndTransport,
} from './setup';
import { installApiHarness } from './apiHarness';

/*
 * WHY : Assumptions: the AUTH CLIENT is stubbed and not the HTTP transport, because every case here
 *       is about what the screen does with an outcome -- which sentence it shows, which control the
 *       cursor lands in, which route it enters -- and not about how a request is serialised. The
 *       client's own contract conformance is asserted where the client is defined, in
 *       `ui/src/api/auth.ts`'s own cases and in `ui/src/api/contracts.test.ts`.
 * WHY : Assumptions: FOUR operations are replaced and not the two the screen calls directly.
 *       `ui/src/hooks/useAuth.ts` reaches `signOut` as `revokeRefreshToken` and arms a renewal timer
 *       that would reach `refreshTokens`, so leaving either real would let a case dispatch a request
 *       -- and no case in this file may reach a network endpoint or read a credential.
 * WHY : Trade-offs: the factory SPREADS the real module rather than replacing it, so every other
 *       export stays authentic. That matters concretely: `USER_ID_MAX_LENGTH` and
 *       `PASSWORD_MAX_LENGTH` are read below as the widths the controls are held to, and a factory
 *       that returned only the four stubs would make both `undefined` and turn two width assertions
 *       into assertions about nothing.
 */
vi.mock(
  '../api/auth',
  /**
   * Replaces the four session exchanges while leaving every other export authentic.
   * @returns {Promise<typeof AuthClient>} The real module with the four operations stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof AuthClient>('../api/auth');
    return {
      ...actual,
      signOn: vi.fn(),
      answerSignOnChallenge: vi.fn(),
      signOut: vi.fn(),
      refreshTokens: vi.fn(),
    };
  },
);

/*
 * WHY : Assumptions: the four modules below are imported DYNAMICALLY, after the factory above is
 *       registered, and the ordering is load-bearing rather than stylistic. Vitest hoists `vi.mock`
 *       above the import block, so any STATIC import whose module graph reaches `../api/auth`
 *       evaluates the factory during that block. `../screens/signon` reaches it directly;
 *       `../hooks/useAuth` reaches it directly; `../router` reaches it through `../layout/AppShell`.
 *       Every other module this file imports was checked and reaches it through neither a value nor a
 *       type import, which is why they stay in the static block where a reader expects them.
 * WHY : Alternatives Considered: importing all of them dynamically for uniformity. Rejected because
 *       a dynamic import carries a claim -- that this module is in the mocked graph -- and applying
 *       it to modules that are not there would make the claim meaningless exactly where it needs to
 *       be read.
 */
const { SIGN_ON_FIELD_LABELS, SIGN_ON_KEY_LABELS, SignOnScreen } =
  await import('../screens/signon');
/*
 * WHY : Assumptions: the identity hook is bound as a NAMESPACE rather than destructured, because one
 *       case asserts over its whole export surface -- that nothing on it can grant a caller a group --
 *       and a destructured binding cannot be enumerated. The three members used directly are then
 *       taken off the namespace so that both readings come from one import.
 */
const authHookModule = await import('../hooks/useAuth');
const { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP, useAuth } = authHookModule;
const { PASSWORD_MAX_LENGTH, USER_ID_MAX_LENGTH, signOn } = await import('../api/auth');
const { ROUTE_TABLE } = await import('../router');

/** The stubbed sign-on exchange, typed through the contract it stands in for. */
const signOnStub = vi.mocked(signOn);

/** The five sentences this screen can show, keyed by the program that emits them. */
const SIGN_ON_MESSAGES = PROGRAM_MESSAGES.COSGN00C;

/** The baseline line each of those five sentences is emitted from. */
const SIGN_ON_MESSAGE_LINES = PROGRAM_MESSAGE_SOURCES.COSGN00C;

/**
 * The identifier every case signs on as, already in the folded form the request carries.
 *
 * Assumptions: it is spelled in upper case here because `app/cbl/COSGN00C.cbl` folds the received
 * field with `FUNCTION UPPER-CASE` at L132-L134 and the screen preserves that fold at submission, so
 * this is the value a request assertion has to compare against. The typed form is deliberately
 * different — see {@link TYPED_USER_ID} — so that a case asserting on the request proves the fold
 * happened rather than merely that the two strings are equal.
 */
const SUBMITTED_USER_ID = 'ADMIN001';

/** The identifier as an operator types it, in the case the fold has to change. */
const TYPED_USER_ID = 'admin001';

/**
 * The credential every case submits.
 *
 * Assumptions: twelve characters with four character classes, because
 * `infra/modules/cognito/variables.tf` declares `password_minimum_length` with a default of 14 and
 * refuses any configuration below 12. A shorter fixture would be a value no environment this
 * repository can express would accept, so a case built on one would assert that the screen forwards a
 * credential guaranteed to be refused.
 */
const SUBMITTED_PASSWORD = 'Secret123!aZ';

/** Identifier the screen gives the operator-identifier control, as it declares it. */
const USER_ID_CONTROL_ID = 'signon-user-id';

/** Identifier the screen gives whichever credential control is mounted, as it declares it. */
const PASSWORD_CONTROL_ID = 'signon-password';

/** Transport status the identity provider's refusals arrive with. */
const UNAUTHENTICATED_STATUS = 401;

/** Transport status a validation refusal carrying per-field entries arrives with. */
const VALIDATION_REFUSED_STATUS = 400;

/**
 * The shortest credential any environment this repository can express will accept.
 *
 * Assumptions: twelve, which is the floor `infra/modules/cognito/variables.tf` enforces on
 * `password_minimum_length` — the variable defaults to 14 and its validation refuses any value below
 * 12. The number is named here so that the credential-width case states what it is measuring against
 * rather than comparing two bounds and leaving a reader to work out why one of them cannot be eight.
 */
const PROVIDER_MINIMUM_PASSWORD_LENGTH = 12;

/**
 * Reports the address the router currently holds, so a route change is observable.
 *
 * Assumptions: the pathname is rendered as this element's whole text, which is what lets a case wait
 * for it with `findByText` instead of polling an assertion inside `waitFor`. No other element in the
 * rendered tree carries a bare route path as its entire text, so the match is unambiguous.
 *
 * Alternatives Considered: mounting the real `MainMenuScreen` and `AdminMenuScreen` at the two onward
 * routes and asserting each one's own mapset title, which is what the co-located suite does. Rejected
 * here for a reason of scope rather than of quality: that assertion measures the destination screens
 * as well as the branch, and those two screens are already exercised through the production route
 * table in `ui/src/routerReachability.test.tsx`. What this file is responsible for is the BRANCH — that
 * the group claim, and nothing else, selects between the two addresses — so the address itself is the
 * whole observation.
 * @returns {ReactElement} An element whose text is the current pathname.
 */
function RouterAddressProbe(): ReactElement {
  const { pathname } = useLocation();
  return <span>{pathname}</span>;
}

/**
 * Returns an element, or throws naming what was looked for.
 *
 * Assumptions: this exists so that no locator in this file needs a type assertion. `querySelector`
 * and `closest` are both typed `Element | null`, and the harness queries take `HTMLElement`, so
 * without a narrowing guard every locator would carry an `as HTMLElement` — an unchecked cast, which
 * this tree forbids, and one that would turn a missing element into a null-dereference several lines
 * away from the query that failed to find it.
 * @param {Element | null} candidate - Whatever a DOM query returned.
 * @param {string} description - What was looked for, quoted back in the failure.
 * @returns {HTMLElement} The element, narrowed.
 * @throws {Error} If nothing was found or what was found is not an HTML element.
 */
function elementOrThrow(candidate: Element | null, description: string): HTMLElement {
  if (!(candidate instanceof HTMLElement)) {
    throw new Error(`${description} is not present in the rendered screen`);
  }
  return candidate;
}

/**
 * Locates one of the screen's two controls by the label the mapset paints beside it.
 *
 * Assumptions: the label is matched with whitespace COLLAPSING TURNED OFF, and that is the reason
 * this wraps the query rather than a case calling `getByLabelText` directly. Both labels carry
 * interior padding that is transcription rather than formatting — `User ID     :` pads with five
 * spaces at `app/bms/COSGN00.bms` L155 and `Password    :` with four at L174, which is what aligned
 * both colons on one column of a character-cell display — and Testing Library's default normaliser
 * collapses those runs to a single space, so a collapsing query accepts a screen that painted
 * `User ID :`. Locating by the padded string therefore asserts the transcription as a side effect of
 * finding the control.
 * @param {string} label - The painted label, from the screen's own exported constants.
 * @returns {HTMLElement} The control the label is associated with.
 * @throws {Error} If no control carries that label, which Testing Library raises.
 */
function controlLabelled(label: string): HTMLElement {
  return screen.getByLabelText(label, {
    normalizer: getDefaultNormalizer({ trim: true, collapseWhitespace: false }),
  });
}

/**
 * Locates the operator-identifier control.
 * @returns {HTMLElement} The identifier input.
 * @throws {Error} If the control is absent.
 */
function userIdControl(): HTMLElement {
  return elementOrThrow(document.getElementById(USER_ID_CONTROL_ID), 'the identifier control');
}

/**
 * Locates whichever credential control is currently mounted.
 * @returns {HTMLElement} The credential input.
 * @throws {Error} If the control is absent.
 */
function passwordControl(): HTMLElement {
  return elementOrThrow(document.getElementById(PASSWORD_CONTROL_ID), 'the credential control');
}

/**
 * Locates the design-system card the screen frames its form in.
 * @returns {HTMLElement} The card element.
 * @throws {Error} If no card is rendered, which means the frame is no longer a `Card`.
 */
function cardFrame(): HTMLElement {
  return elementOrThrow(document.querySelector('.ant-card'), 'the design-system card frame');
}

/**
 * Locates the form item a control is rendered inside.
 * @param {HTMLElement} control - The control whose item is wanted.
 * @returns {HTMLElement} The enclosing form item.
 * @throws {Error} If the control is not inside a form item.
 */
function formItemFor(control: HTMLElement): HTMLElement {
  return elementOrThrow(control.closest('.ant-form-item'), 'the enclosing form item');
}

/**
 * Locates the form's own submit control.
 *
 * Refactoring Rationale: the control is located by its POSITION inside the card and not by its
 * accessible name, and the reason is a measured instability rather than a preference. The name is
 * exactly `Sign on` on a freshly mounted screen, but the design system's `Button` keeps its leaving
 * loading indicator mounted after an exchange settles — an `anticon-loading` span carrying
 * `role="img" aria-label="loading"` — so the computed accessible name becomes `loading Sign on` for
 * the rest of the case. An exact-name query therefore passes before the first submission and fails
 * after it, which is a flake rather than a finding. The card holds exactly one button, so its position
 * is unambiguous, and the label is asserted separately where it belongs.
 * @returns {HTMLElement} The submit button.
 * @throws {Error} If the card holds no button, which Testing Library raises.
 */
function submitControl(): HTMLElement {
  return within(cardFrame()).getByRole('button');
}

/**
 * Locates the shell's function-key legend region.
 * @returns {HTMLElement} The legend's navigation landmark.
 * @throws {Error} If the legend is absent, which Testing Library raises. Absence means the screen
 *   published no bindings, because `ui/src/layout/PfKeyBar.tsx` renders nothing for an empty list.
 */
function keyLegend(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Locates one legend control by the verbatim descriptor the mapset paints for it.
 * @param {string} descriptor - The legend text, from the screen's own exported constants.
 * @returns {HTMLElement} The control that descriptor names.
 * @throws {Error} If no legend control carries that descriptor, which Testing Library raises.
 */
function legendControl(descriptor: string): HTMLElement {
  return within(keyLegend()).getByRole('button', { name: descriptor });
}

/**
 * Builds the token set the provider issues for an operator in the supplied groups.
 *
 * Assumptions: BOTH claims are present and both are required. `ui/src/hooks/useAuth.ts` compares
 * `cognito:username` against the identifier the token set arrived with and refuses a set that names
 * another operator, so a token carrying only the group claim establishes no session at all and every
 * branch case would fail for the wrong reason. `cognito:groups` is the claim the onward branch is
 * decided from.
 *
 * Assumptions: the return type is annotated `SignOnTokens` rather than left inferred, and that
 * annotation is what makes the credential assertion in this file a compile-time guarantee as well as a
 * runtime one. Under an object literal assigned to that type, TypeScript's excess-property check
 * rejects any member the contract does not declare — so a `password` member here would not compile,
 * which is the strongest available statement that no response type carries one.
 * @param {readonly string[]} groups - Group names to place in the `cognito:groups` claim.
 * @returns {SignOnTokens} An authenticated outcome carrying a decodable identity token.
 */
function tokensForGroups(groups: readonly string[]): SignOnTokens {
  const claims = JSON.stringify({
    'cognito:username': SUBMITTED_USER_ID,
    'cognito:groups': groups,
  });
  return {
    outcome: 'AUTHENTICATED',
    userId: SUBMITTED_USER_ID,
    accessToken: 'an-accepted-access-token',
    idToken: `header.${btoa(claims)}.signature`,
    // Assumptions: no renewal token, which is the arrival `ui/src/hooks/useAuth.ts` records as the
    //   likeliest for a first session at a tab. It also keeps the renewal timer out of these cases:
    //   with nothing to renew there is no scheduled exchange for a stub to have to answer.
    refreshToken: null,
    tokenType: 'Bearer',
    // Assumptions: one hour, which is what the pool client is provisioned with --
    //   `access_token_validity` defaults to 60 minutes in `infra/modules/cognito/variables.tf`. It has
    //   to be comfortably longer than the hook's own refresh margin: a shorter lifetime would put the
    //   session inside that margin at the moment it was installed, so every branch case would also be
    //   exercising the renewal path.
    expiresIn: 3600,
  };
}

/**
 * Builds the normalised failure the shared client raises for a service refusal.
 *
 * Assumptions: an `ApiRequestError` is constructed rather than a bare object carrying a `problem`
 * member, because the screen narrows on the class through `isApiRequestError` and reads the transport
 * status from it as well as the document. A hand-rolled look-alike is dropped by that check, and every
 * field-refusal case would then pass against a screen that had rendered nothing at all.
 * @param {number} status - Transport status the refusal carries.
 * @param {string} message - The screen-level sentence, from the message catalog.
 * @param {readonly FieldError[]} [fieldErrors] - Per-field entries, defaulting to none.
 * @returns {ApiRequestError} The rejection to configure the stub with.
 */
function refusalWith(
  status: number,
  message: string,
  fieldErrors: readonly FieldError[] = [],
): ApiRequestError {
  return new ApiRequestError(
    'PROBLEM',
    status,
    apiError({ status, message, fieldErrors }),
    `PROBLEM ${String(status)} CARDDEMO-0400`,
  );
}

/**
 * Returns the route the production route table pairs with one baseline program.
 *
 * Assumptions: the destination is DERIVED from `ui/src/router.tsx` by COBOL program name rather than
 * written out as `/admin` or `/menu` here, and the derivation is the assertion's whole value. The
 * branch being tested is `EXEC CICS XCTL PROGRAM('COADM01C')` at `app/cbl/COSGN00C.cbl` L232 against
 * `PROGRAM('COMEN01C')` at L237, so naming the program and letting the route table supply the address
 * means the case fails if either program is ever remapped — whereas a literal path would keep passing
 * against a table that had moved on.
 * @param {string} program - The baseline program name, as the route table records it.
 * @returns {string} The route that program is migrated to.
 * @throws {Error} If the route table has no entry for that program.
 */
function routeForProgram(program: string): string {
  for (const entry of ROUTE_TABLE) {
    if (entry.program === program) {
      return entry.path;
    }
  }
  throw new Error(`ui/src/router.tsx declares no route for program ${program}`);
}

/**
 * Collects every value held in either browser storage, so a case can search all of them.
 *
 * Assumptions: the search is over VALUES rather than a count of keys, and the difference matters.
 * Asserting both storages are empty would fail the moment any unrelated module cached something
 * harmless, while the property actually under assertion is narrower and permanent: no credential is
 * written to script-readable storage. Searching the values states exactly that.
 * @returns {readonly string[]} Every stored value, from local and session storage together.
 */
function everyStoredValue(): readonly string[] {
  const values: string[] = [];
  for (const store of [window.localStorage, window.sessionStorage]) {
    for (let index = 0; index < store.length; index += 1) {
      const key = store.key(index);
      const value = key === null ? null : store.getItem(key);
      if (value !== null) {
        values.push(value);
      }
    }
  }
  return values;
}

/**
 * Renders the sign-on screen inside the application's own frame, with an address probe beside it.
 *
 * Assumptions: the screen is mounted through `renderInAppShell` and not rendered bare, because it
 * DELEGATES its title band, its row-23 message line and its row-24 legend to the single `AppShell`
 * that `ui/src/App.tsx` mounts. A bare render therefore paints no message band and no legend at all,
 * and every message and key case would fail for a reason that has nothing to do with the screen.
 *
 * Assumptions: no `routePath` is supplied, so the harness mounts the subject at its catch-all child
 * pattern. Trade-offs: that keeps the probe mounted after a successful sign-on has navigated away, so
 * the new address is observable — which is the point — at the cost of leaving the sign-on screen
 * mounted too, where production would have unmounted it. The alternative, pinning the child route to
 * `/signon`, unmounts the probe together with the screen at the exact moment the address changes and
 * leaves the branch unobservable.
 * @returns {Promise<UserEvent>} The operator that drives the rendered screen.
 */
async function mountSignOn(): Promise<UserEvent> {
  const rendered = await renderInAppShell(
    <>
      <SignOnScreen />
      <RouterAddressProbe />
    </>,
    { initialEntries: ['/signon'] },
  );
  return rendered.user;
}

/**
 * Types a complete credential pair into the two mounted controls.
 * @param {UserEvent} user - The operator driving the screen.
 * @returns {Promise<void>} Resolves once both controls hold their value.
 */
async function enterCredentials(user: UserEvent): Promise<void> {
  await user.type(controlLabelled(SIGN_ON_FIELD_LABELS.userId), TYPED_USER_ID);
  await user.type(controlLabelled(SIGN_ON_FIELD_LABELS.password), SUBMITTED_PASSWORD);
}

/**
 * Arranges the harness before each case: no session, no queued answers, no stub behaviour.
 *
 * Assumptions: the request harness is installed even though every network operation is stubbed, and it
 * is not redundant. A successful sign-on installs the issued bearer through `ui/src/api/client.ts`,
 * which builds its instance from the configured base URL — so without the harness the one path that
 * matters most would be exercised against an unconfigured client.
 *
 * Assumptions: the session is discarded BEFORE the harness is installed. `resetSessionAndTransport`
 * removes the transport as well as the session, so installing first and resetting second would take
 * the harness straight back out again.
 * @returns {Promise<void>} Resolves once nothing is rendered, no session is held and the stub is empty.
 */
async function arrangeCase(): Promise<void> {
  await resetSessionAndTransport();
  installApiHarness();
  // Assumptions: the stub is reset explicitly even though `ui/vitest.config.ts` enables `clearMocks`
  //   and `restoreMocks`. Those act AFTER a case, so a case that reads the recorded calls is relying
  //   on the previous case's teardown having run; resetting here states the precondition where the
  //   case can see it.
  signOnStub.mockReset();
}

/**
 * Discards the session and the transport after each case, so nothing leaks into the next file.
 * @returns {Promise<void>} Resolves once no session is held and the transport is the real one again.
 */
async function disposeCase(): Promise<void> {
  await resetSessionAndTransport();
}

/**
 * Waits for an element whose text is one catalogued string, character for character.
 *
 * Assumptions: this is the awaiting counterpart of the harness's own `expectVerbatimMessage` and uses
 * the SAME normaliser, so the two cannot disagree about what verbatim means. Internal whitespace is
 * not collapsed, because several baseline strings carry doubled spaces that are content; boundary
 * whitespace is trimmed, because that is introduced by the markup rather than by the string.
 *
 * Alternatives Considered: `waitFor` wrapping an `expect`. Rejected because `ui/eslint.config.js`
 * configures `jsdoc/require-jsdoc` with `publicOnly: false` and selects an arrow function in every
 * position, so the callback would owe its own block — and Prettier relocates a block comment attached
 * to an inline argument onto the preceding expression, detaching it from what it documents. A
 * `findBy*` query waits by itself and needs no callback at all.
 * @param {string} expected - The catalogued string, taken from the catalog and never retyped. For a
 *   fixed-width entry pass the trimmed text, because HTML collapses the declared-width padding.
 * @returns {Promise<HTMLElement>} The element carrying the string.
 * @throws {Error} If no element carries exactly that text, which Testing Library raises.
 */
async function waitForVerbatimMessage(expected: string): Promise<HTMLElement> {
  return await screen.findByText(expected, {
    normalizer: getDefaultNormalizer({ trim: true, collapseWhitespace: false }),
  });
}

/**
 * Reports whether one control is linked to an element carrying the supplied text.
 *
 * Assumptions: the linkage is followed through `aria-describedby` to the element it names, and the
 * element's text is compared, rather than the identifier being compared against a naming convention
 * spelled out here. The convention belongs to `ui/src/layout/fieldHelp.tsx`; restating it in a test
 * would pin the identifier format instead of the property that matters, which is that a screen-reader
 * user reaches the refusal from the control it is about.
 * @param {HTMLElement} control - The control whose descriptions are followed.
 * @param {string} text - The text one of those descriptions must carry.
 * @returns {boolean} `true` when a described element carries exactly that text.
 */
function isDescribedByText(control: HTMLElement, text: string): boolean {
  const described = control.getAttribute('aria-describedby') ?? '';
  for (const identifier of described.split(' ')) {
    const description = document.getElementById(identifier);
    if (description !== null && description.textContent === text) {
      return true;
    }
  }
  return false;
}

/**
 * Asserts the identifier control carries the width three baseline declarations agree on.
 *
 * Assumptions: the width is asserted through the exported bound the control is actually wired to,
 * and the bound is then pinned to eight in the same case. Asserting the attribute alone would pass
 * against any bound; pinning the constant alone would pass against a control wired to a different
 * one.
 * @returns {Promise<void>} Resolves once both assertions have run.
 */
async function boundsTheIdentifierAtTheCopybookWidth(): Promise<void> {
  await mountSignOn();

  // WHY : Assumptions: eight is not a magic number but the value THREE baseline declarations agree
  //       on -- `LENGTH=8` on `USERID` at `app/bms/COSGN00.bms` L156, `02 USERIDI PIC X(8).` at
  //       `app/cpy-bms/COSGN00.CPY` L72, and `10 CDEMO-USER-ID PIC X(08).` at
  //       `app/cpy/COCOM01Y.cpy` L25. The 3270 terminal enforced that width in hardware, so
  //       `maxLength` is where the constraint survives into a browser and an off-by-one here is a
  //       real fidelity loss rather than a cosmetic one.
  expect(USER_ID_MAX_LENGTH).toBe(8);
  expectMaxLength(userIdControl(), USER_ID_MAX_LENGTH);
}

/**
 * Asserts the credential control is bounded by the service contract, not by the retired field width.
 *
 * Refactoring Rationale: the reference states an eight-character credential width three times —
 * `LENGTH=8` on `PASSWD` at `app/bms/COSGN00.bms` L175, `02 PASSWDI PIC X(8).` at
 * `app/cpy-bms/COSGN00.CPY` L78 and `05 SEC-USR-PWD PIC X(08).` at `app/cpy/CSUSR01Y.cpy` L21 — and
 * all three describe a field this migration does not carry forward. `auth.users` has no credential
 * column and the comparison at `app/cbl/COSGN00C.cbl` L223 has no successor, so the credential now
 * exists only inside a request body on its way to the managed provider. Carrying the width across
 * would not make sign-on stricter, it would make it impossible: the provider accepts nothing shorter
 * than {@link PROVIDER_MINIMUM_PASSWORD_LENGTH}, so an eight-character control would admit only
 * credentials guaranteed to be refused. The divergence is registered as
 * `D-SIGNON-RETIRED-WIDTH-HINT` in `docs/architecture/cobol-to-service-traceability.md`.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function boundsTheCredentialAtTheServiceContract(): Promise<void> {
  await mountSignOn();

  expectMaxLength(passwordControl(), PASSWORD_MAX_LENGTH);
  // WHY : Assumptions: the relation is asserted rather than the number, because what has to hold is
  //       that the control admits a credential the provider can accept. Pinning 256 here would
  //       restate a value `ui/src/api/auth.ts` already publishes and its own cases already pin.
  expect(PASSWORD_MAX_LENGTH).toBeGreaterThanOrEqual(PROVIDER_MINIMUM_PASSWORD_LENGTH);
  expect(PASSWORD_MAX_LENGTH).toBeGreaterThan(USER_ID_MAX_LENGTH);
}

/**
 * Asserts the cursor opens in the identifier control and in no other.
 *
 * Assumptions: the count is taken over every input in the document rather than asserted of the one
 * control, because the property is exclusivity. A second autofocused control would leave which one
 * wins to render order, and an assertion naming only the identifier would pass either way.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function opensWithTheCursorInTheIdentifier(): Promise<void> {
  await mountSignOn();

  const focused: HTMLElement[] = [];
  for (const candidate of document.querySelectorAll('input')) {
    if (document.activeElement === candidate) {
      focused.push(candidate);
    }
  }

  // WHY : Assumptions: exactly one, because `IC` occurs exactly once in the whole mapset -- on
  //       `USERID` at `app/bms/COSGN00.bms` L156 -- and the program corroborates it by moving −1 into
  //       `USERIDL` on first entry at `app/cbl/COSGN00C.cbl` L82.
  expect(focused).toHaveLength(1);
  expect(userIdControl()).toHaveFocus();
}

/**
 * Asserts the credential is masked and that no control offers to reveal it.
 *
 * Trade-offs: a 3270 non-display field renders TRULY BLANK and this renders one dot per character.
 * `PASSWD` at `app/bms/COSGN00.bms` L175 is `ATTRB=(DRK,FSET,UNPROT)`, and the difference is
 * documented design-system gap G2. It is accepted for a specific mechanism rather than on taste: the
 * dots confirm that each keystroke was ACCEPTED while revealing no character, so an operator whose
 * keyboard drops a key sees the count disagree with what they typed and can correct it before
 * submitting, where on a blank field that mistake stays invisible until the exchange is refused.
 * There is no behavioural difference at all — the same characters are submitted either way. What is
 * given up is that an onlooker can read the credential's LENGTH off the screen, and the absent
 * visibility toggle is what keeps the length the only thing recoverable: the design system's default
 * toggle would put the characters themselves one activation away, which is a property the non-display
 * field never had.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function masksTheCredentialWithNoWayToRevealIt(): Promise<void> {
  await mountSignOn();

  const control = passwordControl();
  expect(control).toHaveAttribute('type', 'password');

  const wrapper = elementOrThrow(
    control.closest('.ant-input-password'),
    'the masked-input wrapper',
  );
  const suffix = elementOrThrow(
    wrapper.querySelector('.ant-input-suffix'),
    'the masked-input suffix slot',
  );
  // Assumptions: emptiness of the SUFFIX SLOT is the observable form of `visibilityToggle={false}`.
  //   The design system renders its reveal control into that slot, so an empty slot is the absence of
  //   the control; asserting on the icon's own name instead would tie the case to an icon spelling
  //   that carries no contract.
  expect(suffix.childElementCount).toBe(0);
}

/**
 * Asserts the screen is built from design-system components rather than raw markup.
 *
 * Assumptions: component identity is read from the design system's own class prefixes, which is the
 * only evidence a rendered document carries about which component produced an element. These are
 * structural markers and not design values: no colour, spacing, radius or font literal appears here,
 * and the AAP's zero-hardcoded-values rule is about those.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function framesTheScreenInDesignSystemComponents(): Promise<void> {
  await mountSignOn();

  const card = cardFrame();
  for (const input of card.querySelectorAll('input')) {
    expect(input.className).toContain('ant-input');
  }
  expect(formItemFor(userIdControl())).toBeInTheDocument();
  expect(formItemFor(passwordControl())).toBeInTheDocument();

  const submit = submitControl();
  expect(submit.className).toContain('ant-btn');
  expect(submit).toHaveTextContent(SIGN_ON_SUBMIT_LABEL);
  // Assumptions: the button declares `type="button"`, so pressing Enter inside a field cannot also
  //   trigger the browser's implicit form submission. The screen's Enter binding is the one path that
  //   submits, which is what keeps a single keystroke from dispatching two exchanges.
  expect(submit).toHaveAttribute('type', 'button');
}

/*
 * WHY : Alternatives Considered: every sentence below is asserted through the imported catalog in
 *       `ui/src/messages/messages.ts`, and retyping the words into this file was rejected on a
 *       specific ground rather than on style. A paraphrase in the screen and the same paraphrase in
 *       the test AGREE, so the case passes while the fidelity Transformation Rule T8 exists to
 *       guarantee is already lost. Comparing the screen against the catalog instead means a catalog
 *       entry that ever drifts from the baseline wording fails here, which is the only signal
 *       available -- `tests/README.md` §1.1 records that no CICS runtime exists on the runner, so
 *       there is no golden master for `COSGN00C` to compare against.
 * WHY : Assumptions: each case additionally asserts the catalog's own provenance record for the
 *       sentence, `PROGRAM_MESSAGE_SOURCES.COSGN00C`. That is what ties the citation in the comment
 *       to a checked value: a comment can go stale silently, whereas a recorded line number that
 *       stopped matching fails the case.
 */

/**
 * Asserts a blank identifier is refused first, and that a blank credential is then not reported.
 *
 * Assumptions: the ordering is observable and therefore part of the contract. `EVALUATE TRUE` at
 * `app/cbl/COSGN00C.cbl` L117-L130 tests the identifier at L118 and the credential at L123 and
 * short-circuits, so an operator who leaves BOTH blank is told about the identifier alone and never
 * sees the credential sentence. Reporting both, or testing the credential first, would be a
 * behavioural change rather than a tidier presentation.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesTheIdentifierBeforeTheCredential(): Promise<void> {
  const user = await mountSignOn();

  await user.click(submitControl());

  // WHY : Assumptions: `'Please enter User ID ...'` is emitted at `app/cbl/COSGN00C.cbl` L120, with
  //       its trailing ellipsis and single interior spacing intact.
  expectVerbatimMessage(SIGN_ON_MESSAGES.PLEASE_ENTER_USER_ID);
  expect(SIGN_ON_MESSAGE_LINES.PLEASE_ENTER_USER_ID).toStrictEqual([120]);
  // WHY : Assumptions: the credential sentence at L125 must be ABSENT, which is the short circuit
  //       itself. Both fields are blank, so a screen that reported each failing field would show it.
  expect(screen.queryByText(SIGN_ON_MESSAGES.PLEASE_ENTER_PASSWORD)).toBeNull();
  // WHY : Assumptions: nothing is dispatched, matching `IF NOT ERR-FLG-ON` at L138-L140, which gates
  //       the keyed read on the error flag being off.
  expect(signOnStub).not.toHaveBeenCalled();
  // WHY : Assumptions: the cursor returns to the identifier, which is `MOVE -1 TO USERIDL` at L121.
  expect(userIdControl()).toHaveFocus();
}

/**
 * Asserts a blank credential is refused once the identifier is supplied.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesABlankCredential(): Promise<void> {
  const user = await mountSignOn();

  await user.type(controlLabelled(SIGN_ON_FIELD_LABELS.userId), TYPED_USER_ID);
  await user.click(submitControl());

  // WHY : Assumptions: `'Please enter Password ...'` is emitted at `app/cbl/COSGN00C.cbl` L125.
  expectVerbatimMessage(SIGN_ON_MESSAGES.PLEASE_ENTER_PASSWORD);
  expect(SIGN_ON_MESSAGE_LINES.PLEASE_ENTER_PASSWORD).toStrictEqual([125]);
  expect(signOnStub).not.toHaveBeenCalled();
  // WHY : Assumptions: the cursor returns to the credential, which is `MOVE -1 TO PASSWDL` at L126.
  expect(passwordControl()).toHaveFocus();
}

/*
 * WHY : Assumptions: the three cases below assert where the cursor LANDS, and that is the migration
 *       of `MOVE -1 TO <field>L` -- the CICS convention of writing −1 into a map field's length
 *       subfield so that the `SEND MAP ... CURSOR` which follows positions the cursor there. It is
 *       this screen's ONLY field-level feedback, and that is a property of the reference rather than
 *       a simplification: the `COPY` list at `app/cbl/COSGN00C.cbl` L48-L58 names `COCOM01Y`,
 *       `COSGN00`, `COTTL01Y`, `CSDAT01Y`, `CSMSG01Y`, `CSUSR01Y`, `DFHAID` and `DFHBMSCA` and does
 *       NOT include `CSSETATY`, so the templated red-highlight contract is not applied on this screen
 *       and there is no highlight to reproduce.
 */

/**
 * Asserts the wrong-password sentence is reported and the cursor returns to the credential.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsWrongPasswordAndFocusesTheCredential(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockRejectedValueOnce(
    refusalWith(UNAUTHENTICATED_STATUS, SIGN_ON_MESSAGES.WRONG_PASSWORD_TRY_AGAIN),
  );

  await enterCredentials(user);
  await user.click(submitControl());

  // WHY : Assumptions: `'Wrong Password. Try again ...'` is emitted at `app/cbl/COSGN00C.cbl` L242,
  //       whose literal is continued onto L243, and the service sends it verbatim on a refused
  //       credential.
  await waitForVerbatimMessage(SIGN_ON_MESSAGES.WRONG_PASSWORD_TRY_AGAIN);
  expect(SIGN_ON_MESSAGE_LINES.WRONG_PASSWORD_TRY_AGAIN).toStrictEqual([242]);
  // WHY : Assumptions: this is the one refusal that points at the credential -- `MOVE -1 TO PASSWDL`
  //       at `app/cbl/COSGN00C.cbl` L244, immediately after the sentence it follows.
  expect(passwordControl()).toHaveFocus();
}

/**
 * Asserts the user-not-found sentence is reported and the cursor returns to the identifier.
 *
 * Assumptions: this sentence is unreachable in a deployed environment and the case is still worth
 * having. `infra/modules/cognito/main.tf` fixes `PreventUserExistenceErrors` to `ENABLED`, so the
 * provider answers an unknown identifier and a wrong credential identically and the service has
 * nothing to distinguish them with — the divergence is registered as `D-SIGNON-EXISTENCE-UNIFORM`.
 * What the case pins is the screen's own behaviour if the sentence ever does arrive: the wording is
 * passed through unchanged and the cursor goes to the identifier rather than the credential, which is
 * the branch the reference takes.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsUserNotFoundAndFocusesTheIdentifier(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockRejectedValueOnce(
    refusalWith(UNAUTHENTICATED_STATUS, SIGN_ON_MESSAGES.USER_NOT_FOUND_TRY_AGAIN),
  );

  await enterCredentials(user);
  await user.click(submitControl());

  // WHY : Assumptions: `'User not found. Try again ...'` is emitted at `app/cbl/COSGN00C.cbl` L249,
  //       on the `WHEN 13` arm that a not-found keyed read takes.
  await waitForVerbatimMessage(SIGN_ON_MESSAGES.USER_NOT_FOUND_TRY_AGAIN);
  expect(SIGN_ON_MESSAGE_LINES.USER_NOT_FOUND_TRY_AGAIN).toStrictEqual([249]);
  // WHY : Assumptions: the cursor goes to the identifier -- `MOVE -1 TO USERIDL` at L250 -- because
  //       the identifier is what the operator has to correct.
  expect(userIdControl()).toHaveFocus();
}

/**
 * Asserts a refusal carrying no problem document falls back to the reference's own catch-all sentence.
 *
 * Assumptions: the rejection is a bare `Error` and not an `ApiRequestError`, which is what a transport
 * failure looks like — no response arrived, so there is no document to read a sentence from. That is
 * exactly the condition `WHEN OTHER` at `app/cbl/COSGN00C.cbl` L252-L256 covers, so the fallback is a
 * transcription of the reference's own unclassified-response arm rather than an invention.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function fallsBackToUnableToVerifyAndFocusesTheIdentifier(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockRejectedValueOnce(new Error('the transport did not answer'));

  await enterCredentials(user);
  await user.click(submitControl());

  // WHY : Assumptions: `'Unable to verify the User ...'` is emitted at `app/cbl/COSGN00C.cbl` L254.
  await waitForVerbatimMessage(SIGN_ON_MESSAGES.UNABLE_TO_VERIFY_THE_USER);
  expect(SIGN_ON_MESSAGE_LINES.UNABLE_TO_VERIFY_THE_USER).toStrictEqual([254]);
  // WHY : Assumptions: the cursor goes to the identifier -- `MOVE -1 TO USERIDL` at L255.
  expect(userIdControl()).toHaveFocus();
}

/**
 * Asserts the exit key emits the fifty-character farewell and never the forty-character one.
 *
 * Assumptions: the catalog holds TWO farewells and they are two distinct constants of different
 * declared width and different product wording, so treating them as one string is wrong.
 * `CCDA-MSG-THANK-YOU` is `PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L18-L19 and names "CardDemo";
 * `CCDA-THANK-YOU` is `PIC X(40)` at `app/cpy/COTTL01Y.cpy` L23-L24 and names "CCDA". Neither word
 * appears in the other string, so a width-based or trimmed-form merge would not reconcile them
 * either. `app/cbl/COSGN00C.cbl` L89 is what selects between them, and it moves the fifty-character
 * one — so selecting the other would be a byte-level parity failure that reads as a harmless
 * near-duplicate. The separateness of the two entries is asserted in `ui/src/layout/appShell.test.tsx`;
 * what this case asserts is that the sign-on screen picks the right one.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function signsOffWithTheCardDemoFarewellFromTheKeyboard(): Promise<void> {
  const user = await mountSignOn();

  await pressPfKey(user, 'PFK03');

  // WHY : Assumptions: the trimmed form is compared because the constant carries its declared-width
  //       padding -- the literal is 49 characters against a declared 50 -- and HTML collapses trailing
  //       whitespace on display, so the padding cannot appear in the rendered text. The declared width
  //       itself is asserted from the catalog immediately below, where it is observable.
  await waitForVerbatimMessage(THANK_YOU_CARDDEMO.trim());
  expect(COMMON_MESSAGES.THANK_YOU.declaredWidth).toBe(50);
  expect(COMMON_MESSAGES.THANK_YOU.source.file).toBe('app/cpy/CSMSG01Y.cpy');
  expect(COMMON_MESSAGES.THANK_YOU.source.lines).toStrictEqual([19]);

  // WHY : Assumptions: the forty-character `CCDA-THANK-YOU` must NOT be rendered here. It is the
  //       string a reader reaches for by name, and `app/cbl/COSGN00C.cbl` L89 does not select it.
  expect(screen.queryByText(THANK_YOU_CCDA.trim())).toBeNull();
  expect(SCREEN_TITLES.THANK_YOU.declaredWidth).toBe(40);
  expect(SCREEN_TITLES.THANK_YOU.source.file).toBe('app/cpy/COTTL01Y.cpy');
}

/**
 * Asserts activating the legend's exit control signs off exactly as the exit key does.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function signsOffFromTheLegendExitControl(): Promise<void> {
  const user = await mountSignOn();

  await user.click(legendControl(SIGN_ON_KEY_LABELS.PFK03));

  await waitForVerbatimMessage(THANK_YOU_CARDDEMO.trim());
  expect(screen.queryByText(THANK_YOU_CCDA.trim())).toBeNull();
}

/**
 * Asserts a key this mapset does not paint reports the reference's own invalid-key sentence.
 *
 * Assumptions: the key pressed is PF5, which is a real attention identifier that other screens bind
 * and this one does not. `EVALUATE EIBAID` at `app/cbl/COSGN00C.cbl` L85-L95 has exactly two arms and
 * a `WHEN OTHER` at L91-L94 that sets the error flag, moves `CCDA-MSG-INVALID-KEY` into the message
 * field and re-sends the screen, so this is the migrated form of that arm rather than a generic
 * unknown-input path.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reportsTheInvalidKeySentenceForAnUnboundKey(): Promise<void> {
  const user = await mountSignOn();

  await pressPfKey(user, 'PFK05');

  // WHY : Assumptions: `CCDA-MSG-INVALID-KEY` is `PIC X(50)` at `app/cpy/CSMSG01Y.cpy` L20-L21, and
  //       the trimmed form is compared for the reason the farewell case records.
  await waitForVerbatimMessage(INVALID_KEY_PRESSED.trim());
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(COMMON_MESSAGES.INVALID_KEY.source.file).toBe('app/cpy/CSMSG01Y.cpy');
  expect(COMMON_MESSAGES.INVALID_KEY.source.lines).toStrictEqual([21]);
}

/**
 * Asserts the legend paints exactly the two descriptors the mapset declares, and no third.
 *
 * Assumptions: the two descriptors and their separator are validated against the mapset's own
 * DECLARED WIDTH rather than against a string retyped here. `app/bms/COSGN00.bms` L201-L205 paints
 * the row-24 legend at `LENGTH=22`, and thirteen characters plus two spaces plus seven characters is
 * exactly twenty-two — so joining the two exported descriptors with the two-space separator and
 * measuring the result checks the transcription, the separator width and the completeness of the
 * legend in one assertion, with the transcription itself living only in the screen module.
 *
 * Assumptions: the count of controls is the other half of the same contract. The uniform `F4=Clear`
 * that `ui/src/layout/PfKeyBar.tsx` offers other screens is absent from this mapset, so a third
 * control here would be invented behaviour rather than migrated behaviour.
 *
 * Alternatives Considered: also asserting the PF13-PF24 aliasing and the deliberate absence of a
 * Clear, PA1 and PA2 binding. Rejected because those are shared behaviours of
 * `ui/src/layout/usePfKeys.ts` rather than of this screen, and restating them once per screen is what
 * a shared hook test exists to prevent.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsExactlyTheTwoSourceKeyDescriptors(): Promise<void> {
  await mountSignOn();

  const controls = within(keyLegend()).getAllByRole('button');
  // WHY : Assumptions: two, because `EVALUATE EIBAID` at `app/cbl/COSGN00C.cbl` L85-L95 has exactly
  //       two acting arms -- `WHEN DFHENTER` at L86 and `WHEN DFHPF3` at L88 -- and the mapset paints
  //       exactly two descriptors on row 24 at `app/bms/COSGN00.bms` L201-L205.
  expect(controls).toHaveLength(2);
  expect(controls[0]).toHaveAccessibleName(SIGN_ON_KEY_LABELS.ENTER);
  expect(controls[1]).toHaveAccessibleName(SIGN_ON_KEY_LABELS.PFK03);
  // WHY : Assumptions: twenty-two is the `LENGTH=22` the legend field declares at
  //       `app/bms/COSGN00.bms` L201-L205, and thirteen plus two plus seven is exactly that -- so
  //       measuring the joined descriptors checks the transcription, the two-space separator and the
  //       completeness of the legend at once, with the strings themselves living only in the screen.
  expect(`${SIGN_ON_KEY_LABELS.ENTER}  ${SIGN_ON_KEY_LABELS.PFK03}`).toHaveLength(22);
}

/*
 * WHY : Alternatives Considered: the two submission cases and the two sign-off cases drive the SAME
 *       action through a real key event and through the legend control, and covering only one of the
 *       two was rejected. The 3270 original was keyboard-only, so the key contract is a KEYBOARD
 *       contract: a case that only clicks stays green while every keyboard binding in the application
 *       is broken. The converse also matters, though less: `ui/src/layout/PfKeyBar.tsx` forwards an
 *       activation back through the screen's own `invoke`, so a click that stopped dispatching would
 *       leave the pointer path dead with the keyboard path passing.
 */

/**
 * Asserts the Enter key submits the form, dispatching one exchange and entering the application.
 * @returns {Promise<void>} Resolves once the address has changed.
 */
async function submitsWhenTheEnterKeyIsPressed(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockResolvedValueOnce(tokensForGroups([CARDDEMO_USER_GROUP]));

  await enterCredentials(user);
  await pressPfKey(user, 'ENTER');

  // WHY : Assumptions: `WHEN DFHENTER` at `app/cbl/COSGN00C.cbl` L86-L87 performs the sign-on, and
  //       the destination for an ordinary operator is the program `PROGRAM('COMEN01C')` at L237.
  await screen.findByText(routeForProgram('COMEN01C'));
  expect(signOnStub).toHaveBeenCalledTimes(1);
}

/**
 * Asserts activating the legend's Enter control submits exactly as the Enter key does.
 * @returns {Promise<void>} Resolves once the address has changed.
 */
async function submitsFromTheLegendEnterControl(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockResolvedValueOnce(tokensForGroups([CARDDEMO_USER_GROUP]));

  await enterCredentials(user);
  await user.click(legendControl(SIGN_ON_KEY_LABELS.ENTER));

  await screen.findByText(routeForProgram('COMEN01C'));
  expect(signOnStub).toHaveBeenCalledTimes(1);
}

/*
 * WHY : Refactoring Rationale: the branch the two cases below cover is the single most important
 *       structural change on this screen, and it is a genuine security improvement rather than a
 *       port. In the baseline the program moves the security record's user type into
 *       `CDEMO-USER-TYPE` at `app/cbl/COSGN00C.cbl` L227 and branches on it at L230, and that field
 *       lives in the communication area -- `10 CDEMO-USER-TYPE PIC X(01).` with its `88
 *       CDEMO-USRTYP-ADMIN` and `88 CDEMO-USRTYP-USER` values at `app/cpy/COCOM01Y.cpy` L26-L28.
 *       That area is storage the terminal echoes back between turns, so a client could in principle
 *       have asserted its own user type. In the target the branch is read from the SIGNED
 *       `cognito:groups` claim and from nothing else, so the client cannot assert anything at all.
 * WHY : Assumptions: the consequence for these cases is that authority is arranged ONLY by minting a
 *       token that carries the group. `ui/src/hooks/useAuth.ts` publishes no setter for the groups or
 *       the user type and there is no React context provider anywhere in the application -- the
 *       session is module state surfaced through `useSyncExternalStore` -- so there is no second,
 *       weaker path to authority for a case to take even if one were wanted.
 */

/**
 * Asserts an administrative group claim enters the administrative surface.
 * @returns {Promise<void>} Resolves once the address has changed.
 */
async function entersTheAdministrativeSurfaceForAnAdministrator(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockResolvedValueOnce(tokensForGroups([CARDDEMO_ADMIN_GROUP]));

  await enterCredentials(user);
  await user.click(submitControl());

  // WHY : Assumptions: the destination is the migrated form of `EXEC CICS XCTL PROGRAM('COADM01C')`
  //       at `app/cbl/COSGN00C.cbl` L232, reached by naming that program rather than its address.
  await screen.findByText(routeForProgram('COADM01C'));
  // WHY : Assumptions: the two destinations must differ, which is what makes each branch case an
  //       observation. Were the route table ever to map both programs to one address, both cases
  //       would pass while the branch had stopped existing.
  expect(routeForProgram('COADM01C')).not.toBe(routeForProgram('COMEN01C'));
}

/**
 * Asserts an ordinary group claim enters the ordinary surface.
 * @returns {Promise<void>} Resolves once the address has changed.
 */
async function entersTheOrdinarySurfaceForAnOperator(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockResolvedValueOnce(tokensForGroups([CARDDEMO_USER_GROUP]));

  await enterCredentials(user);
  await user.click(submitControl());

  // WHY : Assumptions: the destination is the migrated form of the `ELSE` arm's
  //       `EXEC CICS XCTL PROGRAM('COMEN01C')` at `app/cbl/COSGN00C.cbl` L237.
  await screen.findByText(routeForProgram('COMEN01C'));
}

/**
 * Asserts nothing on the published surface can grant a caller the administrative group.
 *
 * Assumptions: the assertion is over the SURFACE and not over a behaviour, because that is where the
 * property lives. Authority is derived from the signed claim, so the way to break it is not to
 * mis-derive it but to publish a mutator alongside it — and a mutator is checkable by name. Both
 * surfaces are covered: the module's exports and the hook's own result.
 *
 * Assumptions: an anonymous caller is asserted to be non-administrative with an empty group list,
 * which is the initial state every case starts from. Without it the two branch cases could both be
 * passing against a reading that was administrative all along.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function publishesNoWayToGrantAdministrativeAuthority(): void {
  const authHookExports = Object.keys(authHookModule);
  // Assumptions: the surface is asserted non-empty before it is searched, because a loop over an
  //   empty list passes every assertion inside it. Without this guard a module that stopped exporting
  //   anything at all -- a rename, a botched barrel -- would read as a module exporting no setter.
  expect(authHookExports.length).toBeGreaterThan(0);
  for (const name of authHookExports) {
    // WHY : Assumptions: a setter is recognised by the `set` prefix, which is the only shape a
    //       mutator could take here -- the module exports constants, a decoder, a hook and a test
    //       reset, and every one of those is named for what it reads rather than what it writes.
    expect(name).not.toMatch(/^set/u);
  }

  const { result, unmount } = renderHook(useAuth);
  const publishedMembers = Object.keys(result.current);
  for (const member of publishedMembers) {
    expect(member).not.toMatch(/^set/u);
  }
  expect(result.current.isAdmin).toBe(false);
  expect(result.current.groups).toStrictEqual([]);
  // Assumptions: the probe is unmounted here rather than left to the file's teardown, because it is
  //   the only thing this case rendered and leaving it mounted would hand the next case a listener on
  //   a session it did not establish.
  unmount();
}

/*
 * WHY : Refactoring Rationale: the two cases below exist because of the one place this migration
 *       explicitly DECLINES parity. `app/cpy/CSUSR01Y.cpy` L21 declares
 *       `05 SEC-USR-PWD PIC X(08).` -- an eight-character credential held in clear in the security
 *       record -- and `app/cbl/COSGN00C.cbl` L223 compares it directly. Neither the column nor the
 *       comparison is carried forward: identity moved to a managed user pool, the user table keeps
 *       only a subject reference, and the credential now exists only inside a request body on its way
 *       to that pool. What was wrong with the old approach is not subtle -- a readable credential
 *       store is a total compromise of every account in it, and a direct comparison means the
 *       application handled the clear value on every sign-on.
 * WHY : Assumptions: the consequences observable from THIS screen are the three the first case
 *       asserts -- the credential reaches the request and nothing else, it is not echoed back into the
 *       document, and it is not written to script-readable storage -- plus the shape of the response,
 *       which the second case asserts.
 */

/**
 * Asserts the credential travels no further than the sign-on request.
 * @returns {Promise<void>} Resolves once the assertions have run.
 * @throws {Error} If the exchange was never dispatched, which would make the call assertions vacuous.
 */
async function carriesTheCredentialNoFurtherThanTheRequest(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockRejectedValueOnce(
    refusalWith(UNAUTHENTICATED_STATUS, SIGN_ON_MESSAGES.WRONG_PASSWORD_TRY_AGAIN),
  );

  await enterCredentials(user);
  await user.click(submitControl());
  await waitForVerbatimMessage(SIGN_ON_MESSAGES.WRONG_PASSWORD_TRY_AGAIN);

  expect(signOnStub).toHaveBeenCalledTimes(1);
  const call = signOnStub.mock.calls[0];
  if (call === undefined) {
    throw new Error('the sign-on exchange was never dispatched');
  }
  // WHY : Assumptions: the identifier arrives FOLDED and the credential arrives exactly as typed. The
  //       reference folds both with `FUNCTION UPPER-CASE` at `app/cbl/COSGN00C.cbl` L132-L134 and
  //       L135-L136, but only the identifier's fold has a successor -- the credential's fold existed
  //       to serve the direct comparison at L223, and the pool that compares now is case-sensitive,
  //       so folding would refuse a mixed-case credential the characters as typed would be accepted
  //       for. That is the registered divergence `D-SIGNON-CASE-SENSITIVE-PASSWORD`.
  expect(call[0]).toBe(SUBMITTED_USER_ID);
  expect(call[1]).toBe(SUBMITTED_PASSWORD);

  // WHY : Assumptions: the control is cleared on the refusal, because the reference re-sends the map
  //       with `ERASE` at `app/cbl/COSGN00C.cbl` L151-L157 and a credential must not outlive the
  //       exchange it was submitted for.
  expect(passwordControl()).toHaveValue('');
  // WHY : Assumptions: nothing echoes the credential back into the document. The reference's own user
  //       maintenance screen did exactly that, moving `SEC-USR-PWD` into the map at
  //       `app/cbl/COUSR02C.cbl` L169, and no response this screen reads carries a credential at all.
  expect(screen.queryByText(SUBMITTED_PASSWORD)).toBeNull();

  for (const stored of everyStoredValue()) {
    expect(stored).not.toContain(SUBMITTED_PASSWORD);
  }
}

/**
 * Asserts the issued token set carries no credential member.
 *
 * Assumptions: the compiler is the real gate here and this runtime check documents it. The fixture is
 * annotated `SignOnTokens`, so TypeScript's excess-property check would reject a `password` member at
 * compile time — the assertion below exists so that the guarantee is visible to a reader of the test
 * rather than resting on a type annotation several helpers away.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function acceptsNoCredentialMemberOnTheIssuedTokenSet(): void {
  const issued = tokensForGroups([CARDDEMO_USER_GROUP]);
  for (const member of Object.keys(issued)) {
    expect(member).not.toMatch(/password/iu);
  }
}

/*
 * WHY : Refactoring Rationale: the field-error contract on THIS screen is narrower than the
 *       application-wide one, and the narrowing is the reference's rather than a simplification. The
 *       templated highlight at `app/cpy/CSSETATY.cpy` L17-L27 moves `DFHRED` into a field's colour
 *       subfield when its validation flag is not-OK or blank, and additionally moves a literal `'*'`
 *       into the field when it is blank at L24 -- but `app/cbl/COSGN00C.cbl` does not `COPY CSSETATY`
 *       at all. Its copybook list at L48-L58 names eight books and that is not one of them, so a
 *       blank field on this screen produces a band sentence and a cursor move and nothing else.
 * WHY : Refactoring Rationale: the highlight the copybook does describe is additionally gated on
 *       `CDEMO-PGM-REENTER` at `app/cpy/CSSETATY.cpy` L20, whose `88` value lives at
 *       `app/cpy/COCOM01Y.cpy` L29-L31 -- and that discriminator has no target at all. A stateless
 *       handler has no first-entry-versus-re-entry distinction to make, so the error state is driven
 *       PURELY by the response body and there is no re-entry flag for a case to arrange or assert.
 * WHY : Assumptions: the mapping from a response body onto the controls is performed by the screen
 *       and not by the band, so the screen is where it has to be asserted.
 *       `ui/src/layout/MessageBand.tsx` deliberately refuses an `ApiError` -- per-field text belongs
 *       to a form item's own error state -- so the screen that awaited the call is the only component
 *       holding both the refusal and the controls it addresses.
 */

/**
 * Asserts a service-addressed field refusal is marked on the control it names.
 *
 * Assumptions: the screen-level sentence and the per-field sentence are DIFFERENT catalog entries, so
 * that each assertion locates one element rather than two. Both are catalogued baseline strings
 * because this file retypes no operator-visible text; which of them a real service would send for
 * which condition is the service's contract to state, and what is under assertion here is only that
 * the screen routes the two to their two destinations.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function marksAServiceAddressedRefusalOnItsControl(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockRejectedValueOnce(
    refusalWith(VALIDATION_REFUSED_STATUS, SIGN_ON_MESSAGES.UNABLE_TO_VERIFY_THE_USER, [
      fieldError('userId', SIGN_ON_MESSAGES.PLEASE_ENTER_USER_ID, 'BLANK'),
    ]),
  );

  await enterCredentials(user);
  await user.click(submitControl());
  await waitForVerbatimMessage(SIGN_ON_MESSAGES.PLEASE_ENTER_USER_ID);

  // WHY : Assumptions: the design system's error state on the form item is the migrated form of
  //       moving `DFHRED` into the field's colour subfield at `app/cpy/CSSETATY.cpy` L21-L22, which is
  //       the only rendering the baseline contract asks for beyond the marker.
  expect(formItemFor(userIdControl()).className).toContain('ant-form-item-has-error');
  expect(userIdControl()).toHaveAttribute('aria-invalid', 'true');
  expect(isDescribedByText(userIdControl(), SIGN_ON_MESSAGES.PLEASE_ENTER_USER_ID)).toBe(true);
  // WHY : Assumptions: the screen-level sentence still reaches the band, so the two channels are
  //       populated independently rather than one being derived from the other.
  expectVerbatimMessage(SIGN_ON_MESSAGES.UNABLE_TO_VERIFY_THE_USER);
}

/**
 * Asserts a blank-field refusal marks no control and paints no blank marker.
 *
 * Assumptions: the absence is asserted rather than assumed, because it is the surprising half of the
 * contract — the marker exists in the token bridge, so a reader who finds it there would reasonably
 * expect this screen to paint it. It does not, and the reason is the `COPY` list recorded above. The
 * consequence for accessibility is consistent rather than a gap: `aria-invalid` is emitted from the
 * presence of a field refusal, and on this path there is none, so what tells the operator is the band
 * — which carries an alert role while it holds a sentence — together with the cursor arriving in the
 * field named.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function leavesABlankFieldRefusalUnmarked(): Promise<void> {
  const user = await mountSignOn();

  await user.click(submitControl());
  expectVerbatimMessage(SIGN_ON_MESSAGES.PLEASE_ENTER_USER_ID);

  expect(formItemFor(userIdControl()).className).not.toContain('ant-form-item-has-error');
  expect(userIdControl()).not.toHaveAttribute('aria-invalid');
  // WHY : Assumptions: the marker the token bridge preserves is the literal `'*'` that
  //       `app/cpy/CSSETATY.cpy` L24 moves into a blank field, and it is pinned here so the absence
  //       below is an absence of the right character rather than of whatever the bridge happens to
  //       hold.
  expect(FIELD_ERROR_TOKENS.blankMarker).toBe('*');
  expect(screen.queryByText(FIELD_ERROR_TOKENS.blankMarker)).toBeNull();
}

/**
 * Asserts the refusal colour resolves through the token bridge rather than through a literal.
 *
 * Assumptions: two complementary observations, because either alone is satisfiable by the wrong
 * implementation. The bridge must hold a token NAME — a value the theme resolves — rather than a
 * colour, which is what keeps the AAP's zero-hardcoded-values rule true at the source; and the
 * rendered refusal must carry no inline colour of its own, which is what keeps it true at the point
 * of use. A screen that wrote a hex colour inline would satisfy the first and fail the second.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function resolvesTheRefusalColourThroughTheTokenBridge(): Promise<void> {
  const user = await mountSignOn();
  signOnStub.mockRejectedValueOnce(
    refusalWith(VALIDATION_REFUSED_STATUS, SIGN_ON_MESSAGES.UNABLE_TO_VERIFY_THE_USER, [
      fieldError('userId', SIGN_ON_MESSAGES.PLEASE_ENTER_USER_ID),
    ]),
  );

  await enterCredentials(user);
  await user.click(submitControl());
  const refusal = await waitForVerbatimMessage(SIGN_ON_MESSAGES.PLEASE_ENTER_USER_ID);

  // WHY : Assumptions: the bridge entry is a design-system token name, so it begins with the token
  //       family's own prefix and can never be a colour literal. `ui/src/theme/tokens.ts` records
  //       which shade of the error ramp it points at and the contrast measurement that chose it.
  expect(FIELD_ERROR_TOKENS.errorColor).toMatch(/^color/u);
  expect(refusal.style.color).toBe('');
  expect(userIdControl().style.color).toBe('');
}

/**
 * Registers every sign-on contract case.
 *
 * Assumptions: a named function is passed to `describe` rather than an inline arrow, which is the
 * shape this package uses throughout. `ui/eslint.config.js` configures `jsdoc/require-jsdoc` with
 * `publicOnly: false` and selects an arrow function in every position, and Prettier relocates a block
 * comment attached to an inline argument onto the preceding expression — so a documented inline
 * callback cannot keep its documentation, while a named declaration can.
 * @returns {void} Nothing; the registrations are the outcome.
 */
function signOnContractCases(): void {
  beforeEach(arrangeCase);
  afterEach(disposeCase);

  it(
    'bounds the identifier control at the width three baseline declarations agree on',
    boundsTheIdentifierAtTheCopybookWidth,
  );
  it(
    'bounds the credential control at the service contract rather than the retired field width',
    boundsTheCredentialAtTheServiceContract,
  );
  it(
    'opens with the cursor in the identifier control and in no other',
    opensWithTheCursorInTheIdentifier,
  );
  it('masks the credential and offers no way to reveal it', masksTheCredentialWithNoWayToRevealIt);
  it(
    'frames the screen in design-system components rather than raw markup',
    framesTheScreenInDesignSystemComponents,
  );

  it(
    'refuses a blank identifier before a blank credential and dispatches nothing',
    refusesTheIdentifierBeforeTheCredential,
  );
  it('refuses a blank credential once the identifier is supplied', refusesABlankCredential);
  it(
    'reports the wrong-password sentence and returns the cursor to the credential',
    reportsWrongPasswordAndFocusesTheCredential,
  );
  it(
    'reports the user-not-found sentence and returns the cursor to the identifier',
    reportsUserNotFoundAndFocusesTheIdentifier,
  );
  it(
    'falls back to the unable-to-verify sentence and returns the cursor to the identifier',
    fallsBackToUnableToVerifyAndFocusesTheIdentifier,
  );

  it(
    'signs off with the fifty-character CardDemo farewell and never the forty-character one',
    signsOffWithTheCardDemoFarewellFromTheKeyboard,
  );
  it('signs off from the legend exit control', signsOffFromTheLegendExitControl);
  it(
    'reports the invalid-key sentence for a key this mapset does not paint',
    reportsTheInvalidKeySentenceForAnUnboundKey,
  );
  it(
    'paints exactly the two key descriptors the mapset declares',
    paintsExactlyTheTwoSourceKeyDescriptors,
  );
  it('submits when the Enter key is pressed', submitsWhenTheEnterKeyIsPressed);
  it('submits from the legend Enter control', submitsFromTheLegendEnterControl);

  it(
    'enters the administrative surface for an administrative group claim',
    entersTheAdministrativeSurfaceForAnAdministrator,
  );
  it(
    'enters the ordinary surface for an ordinary group claim',
    entersTheOrdinarySurfaceForAnOperator,
  );
  it(
    'publishes no way for a caller to grant itself administrative authority',
    publishesNoWayToGrantAdministrativeAuthority,
  );

  it(
    'carries the credential no further than the sign-on request',
    carriesTheCredentialNoFurtherThanTheRequest,
  );
  it(
    'accepts no credential member on the issued token set',
    acceptsNoCredentialMemberOnTheIssuedTokenSet,
  );

  it(
    'marks a service-addressed field refusal on the control it names',
    marksAServiceAddressedRefusalOnItsControl,
  );
  it(
    'leaves a blank-field refusal unmarked and paints no blank marker',
    leavesABlankFieldRefusalUnmarked,
  );
  it(
    'resolves the refusal colour through the token bridge rather than a literal',
    resolvesTheRefusalColourThroughTheTokenBridge,
  );
}

describe('sign-on screen contracts', signOnContractCases);
