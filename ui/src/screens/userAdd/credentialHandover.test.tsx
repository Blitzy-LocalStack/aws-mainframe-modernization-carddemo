/**
 * @file Proves the user-add screen collects no credential and hands the generated one over exactly once.
 *
 * Purpose
 * -------
 * `app/bms/COUSR01.bms` L121-L135 paints a `Password:` label, an eight-character non-display input and an
 * `(8 Char)` hint, and `app/cbl/COUSR01C.cbl` refuses that input when blank (L136-L141) and moves the
 * submitted value into the record (L157). None of that has a target analogue: the credential is GENERATED
 * by the service, `CreateUserRequest` is sealed at four members and none is a credential, and the value
 * comes back on the create response instead. Two divergences register the halves --
 * `D-USER-ADD-NO-CREDENTIAL-CONTROL` for the withdrawn control and `D-RUNTIME-CREDENTIAL-HANDOVER` for
 * the returned value -- and the cases below are where both are held to what shipped.
 *
 * Refactoring Rationale: these cases exist because TWO revisions of this journey failed in ways every
 * visible expectation passed. The screen rendered a credential control, refused it when blank, retained
 * the characters an administrator typed and then dropped them unsent -- so a control appeared, a refusal
 * appeared and a write succeeded, while the value reached nothing. And the service returned only the name
 * of the managed-secret entry the real credential was archived to, which needs a grant a browser session
 * does not hold -- so the create succeeded and the account was unreachable. Only assertions about what is
 * ABSENT catch the first, and only an assertion that the returned value is DISPLAYED catches the second,
 * which is why this file asserts both directions.
 *
 * Refactoring Rationale: every function passed to `vi.mock`, `describe`, `it` and `waitFor` is a NAMED
 * declaration, which is the shape `ui/src/screens/userUpdate/credentialControl.test.tsx` records:
 * `ui/eslint.config.js` selects a function expression in every position, so an inline callback needs its
 * own JSDoc block, and Prettier moves a block comment that follows an argument comma onto the preceding
 * string literal, which detaches the block from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { createUser } from '../../api/auth';
import type { CreatedUserResponse } from '../../api/types';
import { AppShell } from '../../layout/AppShell';
import { CREDENTIAL_HANDOVER_MESSAGES, SHARED_MESSAGES } from '../../messages/messages';
import {
  USER_ADD_CREDENTIAL_TEST_ID,
  USER_ADD_FIELD_LABELS,
  USER_ADD_FIELD_WIDTHS,
  UserAddScreen,
} from './index';

/**
 * Builds the mocked surface of the identity transport module.
 *
 * Assumptions: a hoisted function DECLARATION rather than an inline factory, because Vitest lifts every
 * `vi.mock` call above the imports and a factory held in a `const` would be in its temporal dead zone at
 * registration time.
 *
 * Assumptions: `USER_ID_MAX_LENGTH` is restated rather than spread from the real module, because the
 * factory may not reach the module it replaces. Its value is the `05 SEC-USR-ID PIC X(08).` width at
 * `app/cpy/CSUSR01Y.cpy` L18, which the screen applies as the identifier control's `maxLength`.
 * @returns {Record<string, unknown>} The create operation as a spy, plus the width constant.
 */
function mockIdentityTransportModule(): Record<string, unknown> {
  return {
    USER_ID_MAX_LENGTH: 8,
    createUser: vi.fn(),
  };
}

/*
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, because these cases
 * assert what the screen SENDS and what it paints, which are properties of the screen rather than of the
 * interceptor chain. Mocking the client would make a body-shape regression indistinguishable from a
 * transport one.
 */
vi.mock('../../api/auth', mockIdentityTransportModule);

/** The identifier a case types, eight characters as the identifier control declares. */
const TYPED_USER_ID = 'USER0042';

/**
 * The credential the substituted service answers with.
 *
 * Assumptions: it is deliberately unlike every other literal in this file, and unlike the archive locator
 * below, so a case asserting the value is on the glass cannot pass by finding something else and a case
 * asserting it is GONE cannot pass by coincidence.
 */
const RETURNED_CREDENTIAL = 'Qx7$mZp2Wr9!Kt4v';

/** The archive locator the same response carries, which this screen must never render. */
const RETURNED_SECRET_NAME = 'carddemo/dev/auth/runtime-user/9f2c4a7b1e6d05384c9a1b2d3e4f5061';

/** The created row as the service answers a successful write, carrying both additions. */
const CREATED_ROW: CreatedUserResponse = {
  userId: TYPED_USER_ID,
  firstName: 'ADA',
  lastName: 'LOVELACE',
  userType: 'A',
  cognitoSub: '11111111-2222-3333-4444-555555555555',
  credentialSecretName: RETURNED_SECRET_NAME,
  oneTimeCredential: RETURNED_CREDENTIAL,
};

/**
 * Mounts the add screen inside the shell that owns its title band, message line and key legend.
 *
 * Assumptions: the screen is rendered INSIDE `AppShell`, not bare. It composes none of those three itself
 * -- `ui/src/router.tsx` mounts the shell as a layout route -- so a bare render produces a screen with no
 * legend and no band, and every query for either fails on a screen that is in fact correct.
 * @returns {{ unmount: () => void }} The render result, narrowed to the one control a case uses.
 */
function renderAddScreen(): { unmount: () => void } {
  const rendered = render(
    <MemoryRouter initialEntries={['/users/new']}>
      <AppShell>
        <UserAddScreen />
      </AppShell>
    </MemoryRouter>,
  );

  return { unmount: rendered.unmount };
}

/**
 * Fills the four controls the screen declares and presses the submit key.
 *
 * Assumptions: the user type is typed as the single admitted upper-case character, because the control is
 * one character wide and the screen narrows the value to the two-literal domain before sending.
 * @returns {Promise<void>} Resolves once the submit key has been pressed.
 */
async function fillTheFourControlsAndSubmit(): Promise<void> {
  await userEvent.type(
    screen.getByLabelText(USER_ADD_FIELD_LABELS.firstName),
    CREATED_ROW.firstName,
  );
  await userEvent.type(screen.getByLabelText(USER_ADD_FIELD_LABELS.lastName), CREATED_ROW.lastName);
  await userEvent.type(screen.getByLabelText(USER_ADD_FIELD_LABELS.userId), TYPED_USER_ID);
  await userEvent.type(screen.getByLabelText(USER_ADD_FIELD_LABELS.userType.trim()), 'A');
  await userEvent.click(screen.getByRole('button', { name: /ENTER=Add User/u }));
}

/**
 * Waits until the handover surface carries the returned credential.
 * @returns {Promise<void>} Resolves once the value is on the screen.
 */
async function waitForTheCredentialToLand(): Promise<void> {
  await waitFor(
    /**
     * Waits until the returned credential has reached the document.
     * @returns {void} Nothing; throws until the value is rendered.
     */
    () => {
      expect(screen.getByText(RETURNED_CREDENTIAL)).toBeInTheDocument();
    },
  );
}

/**
 * Restores the module mock between cases so one case's answer cannot leak into the next.
 * @returns {void} Nothing.
 */
function resetIdentityMocks(): void {
  vi.mocked(createUser).mockReset();
}

/**
 * Asserts no credential control is painted, by any of the three ways the mapset would identify one.
 *
 * Assumptions: the label, the hint and the input TYPE are all asserted, because each is a separate way the
 * control could return. A masked input with no label would still put a credential prompt on the glass, and
 * a label with no input would still advertise one.
 *
 * Assumptions: the `(8 Char)` hint is asserted to appear exactly ONCE rather than not at all. The mapset
 * paints it twice -- beside the identifier at L116-L120 and beside the credential at L131-L135 -- and the
 * identifier's is carried, so an absence assertion would fail on a correct screen while a count catches
 * the credential's return.
 * ⚠️ Assumptions: this helper is SYNCHRONOUS. Every query it makes reads the tree the render has
 * already produced, and none of them settles an effect, so there is nothing here to await; declaring it
 * `async` would have made a reader look for the asynchronous step and would have put an unresolved
 * promise in the runner's hands for no reason. `it` accepts either shape.
 * @returns {void} Nothing; failure is reported by the expectation that fails.
 */
function paintsNoCredentialControl(): void {
  renderAddScreen();

  expect(screen.queryByLabelText(/password/iu)).toBeNull();
  expect(screen.getAllByText('(8 Char)')).toHaveLength(1);
  expect(document.querySelectorAll('input[type="password"]')).toHaveLength(0);
}

/**
 * Asserts the screen paints exactly the FOUR controls it declares, and no fifth.
 *
 * Assumptions: three counts are taken and each catches something the others cannot. The label query
 * cannot see a fifth control by construction; the `textbox` role count cannot see a masked input, which is
 * roleless to the accessibility tree; only counting the form's raw `input` elements fails when a
 * credential control comes back.
 *
 * Assumptions: the label record and the width record are asserted to have the same four keys as the field
 * union, because the renderer reads a label and a width per field -- so a restored control would have to
 * grow one of them, and a check on the rendered output alone would pass against a half-restored one.
 *
 * Assumptions: each label is TRIMMED for the query even though the rendered value is not. The user-type
 * label is `'User Type: '` -- the eleventh character is a space the mapset declares at `LENGTH=11` and the
 * screen keeps verbatim -- while the DOM-testing default normaliser collapses and trims whitespace before
 * matching, so an untrimmed query cannot match a label it rendered itself.
 * ⚠️ Assumptions: this helper is SYNCHRONOUS, for the reason recorded on the case above -- the three
 * counts read a tree the render has already produced and settle nothing.
 * @returns {void} Nothing; failure is reported by the expectation that fails.
 */
function paintsFourLabelledControls(): void {
  renderAddScreen();

  const labelled = Object.values(USER_ADD_FIELD_LABELS).map(
    /**
     * Reads one control by the label the mapset paints beside it.
     * @param {string} label - Verbatim label text, trimmed to survive the default normaliser.
     * @returns {HTMLElement} The control rendered for that label.
     */
    (label: string): HTMLElement => screen.getByLabelText(label.trim()),
  );

  expect(labelled).toHaveLength(4);
  expect(document.querySelectorAll('form input')).toHaveLength(4);
  expect(screen.getAllByRole('textbox')).toHaveLength(4);
  expect(Object.keys(USER_ADD_FIELD_LABELS).sort()).toEqual([
    'firstName',
    'lastName',
    'userId',
    'userType',
  ]);
  expect(Object.keys(USER_ADD_FIELD_WIDTHS).sort()).toEqual(
    Object.keys(USER_ADD_FIELD_LABELS).sort(),
  );
}

/**
 * Asserts a blanked user type raises the USER-TYPE sentence, with no credential arm able to intervene.
 *
 * Assumptions: the user type is the control left empty because it is the LAST arm of the reference's
 * cascade and the credential's arm sat immediately before it. So this is the reachability proof: a screen
 * still demanding a credential would answer the credential's arm first and the user type's own refusal
 * would be unreachable from a browser, which is exactly the state the retired revision was in.
 *
 * Assumptions: the sentence is expected TWICE, because this screen raises a refusal on both surfaces the
 * reference does -- the row-23 message line and the control's own `Form.Item` help text, which is the
 * templated `app/cpy/CSSETATY.cpy` highlight. A single-match query fails on the count rather than the
 * content, which reads as "the refusal is missing" when it is present on both.
 * @returns {Promise<void>} Resolves once the refusal is on the screen.
 */
async function refusesTheUserTypeWithNoCredentialArmAheadOfIt(): Promise<void> {
  renderAddScreen();

  await userEvent.type(
    screen.getByLabelText(USER_ADD_FIELD_LABELS.firstName),
    CREATED_ROW.firstName,
  );
  await userEvent.type(screen.getByLabelText(USER_ADD_FIELD_LABELS.lastName), CREATED_ROW.lastName);
  await userEvent.type(screen.getByLabelText(USER_ADD_FIELD_LABELS.userId), TYPED_USER_ID);
  await userEvent.click(screen.getByRole('button', { name: /ENTER=Add User/u }));

  expect(await screen.findAllByText('User Type can NOT be empty...')).toHaveLength(2);
  expect(screen.queryByText(SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY)).toBeNull();
  expect(vi.mocked(createUser)).not.toHaveBeenCalled();
}

/**
 * Asserts the create request carries exactly the four contract members and no credential.
 *
 * Assumptions: the body's own key set is asserted rather than only its named members, because
 * `toMatchObject` would pass on a body carrying a fifth. A collected credential would reach the transport
 * as that fifth member, and the sealed contract would reject it at the service -- which is a failure an
 * operator sees rather than one a test does, unless the key set is counted here.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function sendsFourMembersOnly(): Promise<void> {
  vi.mocked(createUser).mockResolvedValue(CREATED_ROW);

  renderAddScreen();
  await fillTheFourControlsAndSubmit();

  await waitFor(
    /**
     * Waits until the write has been reached.
     * @returns {void} Nothing; throws until the call has been recorded.
     */
    () => {
      expect(vi.mocked(createUser)).toHaveBeenCalledTimes(1);
    },
  );

  const body = vi.mocked(createUser).mock.calls[0]?.[0];

  expect(Object.keys(body ?? {}).sort()).toEqual(['firstName', 'lastName', 'userId', 'userType']);
  expect(body).toEqual({
    firstName: CREATED_ROW.firstName,
    lastName: CREATED_ROW.lastName,
    userId: TYPED_USER_ID,
    userType: CREATED_ROW.userType,
  });
}

/**
 * Asserts the returned credential is displayed once, with a copy control, and the locator beside it is not.
 *
 * Assumptions: ⚠️ this is the case the second broken revision would have failed. It returned only the
 * archive locator, so a screen reading a credential off the response had nothing to read; asserting the
 * VALUE is present -- and that it is the value the response carried, not merely that a surface appeared --
 * is what distinguishes a working handover from an empty one.
 *
 * Assumptions: the locator's ABSENCE is asserted in the same case. It names the entry the same credential
 * was archived to, and reading that entry needs a grant this operator's session does not hold, so
 * rendering it would offer an action they cannot take while disclosing where the value is kept.
 *
 * Assumptions: the copy control is located by its authored accessible name rather than by a class or a
 * position. The design system derives that name from the first tooltip, so a control whose name is missing
 * is a control a keyboard operator cannot identify -- which is the whole point of publishing the string.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function displaysTheReturnedCredentialOnce(): Promise<void> {
  vi.mocked(createUser).mockResolvedValue(CREATED_ROW);

  renderAddScreen();
  await fillTheFourControlsAndSubmit();
  await waitForTheCredentialToLand();

  const surface = screen.getByTestId(USER_ADD_CREDENTIAL_TEST_ID);

  expect(surface).toHaveTextContent(CREDENTIAL_HANDOVER_MESSAGES.TITLE);
  expect(surface).toHaveTextContent(CREDENTIAL_HANDOVER_MESSAGES.EXPLANATION);
  expect(screen.getAllByText(RETURNED_CREDENTIAL)).toHaveLength(1);
  expect(
    screen.getByRole('button', { name: CREDENTIAL_HANDOVER_MESSAGES.COPY_CONTROL }),
  ).toBeInTheDocument();
  expect(document.body.textContent ?? '').not.toContain(RETURNED_SECRET_NAME);
}

/**
 * Asserts dismissing the surface removes the credential, and that a remount does not recover it.
 *
 * Assumptions: the assertion after the dismissal is over the WHOLE document text and not over the surface
 * alone, because a value moved into a hidden node, an attribute or the message band would satisfy a
 * surface-scoped query while still being on the page.
 *
 * Assumptions: the remount is asserted in the same case, because component state is the only thing holding
 * the value and "state is the only holder" is a claim about what happens when the state is gone. A screen
 * that had written the value anywhere durable would repaint it here.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function dismissingTheSurfaceDiscardsTheCredential(): Promise<void> {
  vi.mocked(createUser).mockResolvedValue(CREATED_ROW);

  const first = renderAddScreen();
  await fillTheFourControlsAndSubmit();
  await waitForTheCredentialToLand();

  await userEvent.click(
    screen.getByRole('button', { name: CREDENTIAL_HANDOVER_MESSAGES.DISMISS_CONTROL }),
  );

  expect(screen.queryByTestId(USER_ADD_CREDENTIAL_TEST_ID)).toBeNull();
  expect(document.body.textContent ?? '').not.toContain(RETURNED_CREDENTIAL);

  first.unmount();
  renderAddScreen();

  expect(screen.queryByTestId(USER_ADD_CREDENTIAL_TEST_ID)).toBeNull();
  expect(document.body.textContent ?? '').not.toContain(RETURNED_CREDENTIAL);
}

/**
 * Asserts nothing in the journey writes the credential to browser storage or to the address bar.
 *
 * Assumptions: ⚠️ the storage setters are SPIED rather than read back afterwards, because a value written
 * and then removed would leave nothing to read while still having been persisted -- and a persisted
 * credential is disclosed for as long as the write lasts, not for as long as it remains. The spy catches
 * the write itself.
 *
 * Assumptions: both storages are covered, and so is the location. The published contract marks this
 * response `Cache-Control: no-store` and states the client's half of that obligation as "not to storage, a
 * URL, a query string, a route parameter, a form field or a log"; the first three are mechanically
 * checkable here and are checked.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function writesTheCredentialToNoStorage(): Promise<void> {
  vi.mocked(createUser).mockResolvedValue(CREATED_ROW);

  const session = vi.spyOn(window.sessionStorage, 'setItem');
  const local = vi.spyOn(window.localStorage, 'setItem');
  try {
    renderAddScreen();
    await fillTheFourControlsAndSubmit();
    await waitForTheCredentialToLand();

    const written = [...session.mock.calls, ...local.mock.calls].map(
      /**
       * Renders one storage write as a single string, so a key or a value carrying the credential is seen.
       * @param {readonly unknown[]} call - The arguments of one `setItem` call.
       * @returns {string} The call's arguments joined into one inspectable string.
       */
      (call: readonly unknown[]): string => call.map(String).join(' '),
    );

    expect(written.join('\n')).not.toContain(RETURNED_CREDENTIAL);
    expect(window.location.href).not.toContain(RETURNED_CREDENTIAL);
    expect(window.location.search).not.toContain(RETURNED_CREDENTIAL);
  } finally {
    session.mockRestore();
    local.mockRestore();
  }
}

/**
 * Registers the seven cases.
 * @returns {void} Nothing.
 */
function credentialHandoverCases(): void {
  afterEach(resetIdentityMocks);

  it('paints no credential control', paintsNoCredentialControl);
  it('paints exactly the four controls it declares', paintsFourLabelledControls);
  it(
    'refuses a blank user type with no credential arm ahead of it',
    refusesTheUserTypeWithNoCredentialArmAheadOfIt,
  );
  it('sends the four contract members and no credential', sendsFourMembersOnly);
  it(
    'displays the returned credential once, with a copy control',
    displaysTheReturnedCredentialOnce,
  );
  it(
    'discards the credential when the surface is dismissed',
    dismissingTheSurfaceDiscardsTheCredential,
  );
  it('writes the credential to no storage and to no address', writesTheCredentialToNoStorage);
}

describe(
  'the add screen collects no credential and hands the generated one over once',
  credentialHandoverCases,
);
