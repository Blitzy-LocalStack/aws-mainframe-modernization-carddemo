/**
 * @file Proves the user-update screen paints no credential control and refuses nothing on its behalf.
 *
 * Purpose
 * -------
 * `app/bms/COUSR02.bms` L125-L139 paints a `Password:` label, an eight-character non-display input and an
 * `(8 Char)` hint, and `app/cbl/COUSR02C.cbl` refuses that input when blank (L198-L203), compares it
 * (L227-L230) and writes it (L358-L390). None of those four behaviours has a target analogue: AAP section
 * 0.4.1.3 records that the plaintext credential "is deliberately not carried forward", `UpdateUserRequest`
 * is sealed at three members, and no auth-service operation accepts or resets a credential. The screen
 * therefore renders four controls where the mapset paints five, and the divergence is registered as
 * `D-USER-UPDATE-CREDENTIAL-CONTROL`.
 *
 * Refactoring Rationale: these cases exist because an earlier revision DID render the control -- it
 * refused it when blank, retained the eight characters an administrator typed and then discarded them
 * unsent. Every visible expectation of that arrangement passed: a control appeared, a refusal appeared,
 * a write succeeded. Only an assertion about what is ABSENT can catch it, which is why the first three
 * cases assert absence rather than presence, and why the fourth counts the members of the request body
 * -- a fourth member would be the other way the same defect could return.
 *
 * Refactoring Rationale: every function passed to `vi.mock`, `describe`, `it` and `waitFor` is a NAMED
 * declaration, which is the shape `ui/src/screens/transactionAdd/copyLast.test.tsx` records:
 * `ui/eslint.config.js` selects a function expression in every position, so an inline callback needs its
 * own JSDoc block, and Prettier moves a block comment that follows an argument comma onto the preceding
 * string literal, which detaches the block from the function it documents.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';

import { AppShell } from '../../layout/AppShell';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { getUser, updateUser } from '../../api/auth';
import type { UserResponse } from '../../api/types';
import { USER_UPDATE_FIELD_LABELS, UserUpdateScreen } from './index';

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
 * @returns {Record<string, unknown>} The two identity operations as spies, plus the width constant.
 */
function mockIdentityTransportModule(): Record<string, unknown> {
  return {
    USER_ID_MAX_LENGTH: 8,
    getUser: vi.fn(),
    updateUser: vi.fn(),
  };
}

/*
 * Assumptions: the transport module is mocked rather than the HTTP client beneath it, because these cases
 * assert what the screen SENDS and what it paints, which are properties of the screen rather than of the
 * interceptor chain. Mocking the client would make a body-shape regression indistinguishable from a
 * transport one.
 */
vi.mock('../../api/auth', mockIdentityTransportModule);

/** The row the operator is editing, eight characters as the identifier field declares. */
const EDITED_USER_ID = 'USER0001';

/** The row as the service answers a read, with values distinct from anything a case types. */
const STORED_ROW: UserResponse = {
  userId: EDITED_USER_ID,
  firstName: 'JOHN',
  lastName: 'DOE',
  userType: 'U',
  cognitoSub: '11111111-2222-3333-4444-555555555555',
};

/** The row as the service answers a write, carrying the edited first name back. */
const SAVED_ROW: UserResponse = { ...STORED_ROW, firstName: 'JANE' };

/**
 * Mounts the update screen behind its own route so the identifier reaches it as the router supplies it.
 *
 * Assumptions: `MemoryRouter` with an explicit `Route` rather than the application router, because the
 * screen reads its subject from the `id` path parameter -- mounting it bare would leave that parameter
 * undefined and exercise the manual-entry arm instead of the routed one.
 * @returns {void} Nothing; the screen is rendered into the test document.
 */
function renderUpdateScreen(): void {
  render(
    <MemoryRouter initialEntries={[`/users/${EDITED_USER_ID}/edit`]}>
      {/*
        WHY : ⚠️ Refactoring Rationale: the screen is rendered INSIDE `AppShell`, where it was rendered
              bare. The screen delegates its title band, its row-23 message line and its row-24 legend to
              the one shell that `ui/src/router.tsx` mounts as a layout route -- it composes none of the
              three itself -- so a bare render produced a screen with no legend and no band, and every
              query for either failed on a screen that is in fact correct. The children form is used
              rather than a layout route because it is the shape that needs no second route level, and
              `AppShell` renders `children ?? <Outlet />`, so both forms paint the same frame.
      */}
      <AppShell>
        <Routes>
          <Route path="/users/:id/edit" element={<UserUpdateScreen />} />
        </Routes>
      </AppShell>
    </MemoryRouter>,
  );
}

/**
 * Waits until the routed read has seeded the editable controls.
 * @returns {Promise<void>} Resolves once the stored first name is on the screen.
 */
async function waitForTheRowToLand(): Promise<void> {
  await waitFor(
    /**
     * Waits until the stored first name has reached its control.
     * @returns {void} Nothing; throws until the value is applied.
     */
    () => {
      expect(screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName)).toHaveValue(
        STORED_ROW.firstName,
      );
    },
  );
}

/**
 * Restores the module mocks between cases so one case's answer cannot leak into the next.
 * @returns {void} Nothing.
 */
function resetIdentityMocks(): void {
  vi.mocked(getUser).mockReset();
  vi.mocked(updateUser).mockReset();
}

/**
 * Asserts no credential control is painted, by any of the three ways the mapset would identify one.
 *
 * Assumptions: the label, the hint and the input TYPE are all asserted, because each is a separate way the
 * control could return. A masked input with no label would still put a credential prompt on the glass, and
 * a label with no input would still advertise one.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function paintsNoCredentialControl(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(STORED_ROW);

  renderUpdateScreen();
  await waitForTheRowToLand();

  expect(screen.queryByLabelText(/password/iu)).toBeNull();
  expect(screen.queryByText('(8 Char)')).toBeNull();
  expect(document.querySelectorAll('input[type="password"]')).toHaveLength(0);
}

/**
 * Asserts the screen paints exactly the FOUR controls it declares, and no fifth.
 *
 * Assumptions: the count is asserted over the labelled controls rather than over every `input`, because
 * antd renders no unlabelled input on this screen and a count over the raw elements would silently absorb
 * one if it ever did.
 *
 * Assumptions: each label is TRIMMED for the query even though the rendered value is not. The user-type
 * label is `'User Type: '` -- the eleventh character is a space the mapset declares at `LENGTH=11` and the
 * screen keeps verbatim -- while the DOM-testing default normaliser collapses and trims whitespace before
 * matching, so an untrimmed query cannot match a label it rendered itself. Trimming on the query side
 * leaves the rendered string exactly as transcribed, which is the property that matters.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function paintsFourLabelledControls(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(STORED_ROW);

  renderUpdateScreen();
  await waitForTheRowToLand();

  const labelled = Object.values(USER_UPDATE_FIELD_LABELS).map(
    /**
     * Reads one control by the label the mapset paints beside it.
     * @param {string} label - Verbatim label text, trimmed to survive the default normaliser.
     * @returns {HTMLElement} The control rendered for that label.
     */
    (label: string): HTMLElement => screen.getByLabelText(label.trim()),
  );

  expect(labelled).toHaveLength(4);

  /*
   * WHY : Assumptions: the form's raw `input` elements are counted as well as its labelled and
   *       textbox-roled ones, because neither of those two catches an EXTRA control. A query by the four
   *       declared labels cannot see a fifth by construction, and a masked input carries no `textbox`
   *       role at all -- `input[type="password"]` is roleless to the accessibility tree -- so a
   *       reinstated credential control would satisfy both. Counting the elements is the only form of
   *       this assertion that fails when one comes back.
   */
  expect(document.querySelectorAll('form input')).toHaveLength(4);
  expect(screen.getAllByRole('textbox')).toHaveLength(4);
}

/**
 * Asserts a blanked first name raises the FIRST-NAME sentence, with no credential arm able to intervene.
 *
 * Assumptions: the first name is blanked rather than the identifier, because the reference's short-circuit
 * order is identifier, first name, last name, credential, user type -- so blanking the first name proves
 * both that the surviving arms keep their order and that no removed arm silently took its place.
 *
 * Assumptions: the sentence is expected TWICE and asserted as such rather than singly, because this screen
 * raises a refusal on both of the surfaces the reference does: `MessageBand` carries it as the row-23
 * message and the control's own `Form.Item` carries it as `help` beneath the field, which is the templated
 * `app/cpy/CSSETATY.cpy` highlight. A single-match query fails on the count rather than on the content,
 * which would read as "the refusal is missing" when it is in fact present on both.
 * @returns {Promise<void>} Resolves once the refusal is on the screen.
 */
async function refusesTheFirstNameWithoutACredentialArm(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(STORED_ROW);

  renderUpdateScreen();
  await waitForTheRowToLand();

  await userEvent.clear(screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName));
  await userEvent.click(screen.getByRole('button', { name: /F5=Save/u }));

  expect(await screen.findAllByText('First Name can NOT be empty...')).toHaveLength(2);
  expect(screen.queryByText('Password can NOT be empty...')).toBeNull();
  expect(vi.mocked(updateUser)).not.toHaveBeenCalled();
}

/**
 * Asserts the write carries exactly the three contract members and no credential.
 *
 * Assumptions: the body's own key set is asserted rather than only its named members, because
 * `toMatchObject` would pass on a body carrying a fourth. A retained credential would reach the transport
 * as that fourth member, and the sealed contract would reject it at the service -- which is a failure an
 * operator sees rather than one a test does, unless the key set is counted here.
 * @returns {Promise<void>} Resolves once every assertion has run.
 */
async function sendsThreeMembersOnly(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(STORED_ROW);
  vi.mocked(updateUser).mockResolvedValue(SAVED_ROW);

  renderUpdateScreen();
  await waitForTheRowToLand();

  await userEvent.clear(screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName));
  await userEvent.type(
    screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName),
    SAVED_ROW.firstName,
  );
  await userEvent.click(screen.getByRole('button', { name: /F5=Save/u }));

  await waitFor(
    /**
     * Waits until the write has been reached.
     * @returns {void} Nothing; throws until the call has been recorded.
     */
    () => {
      expect(vi.mocked(updateUser)).toHaveBeenCalledTimes(1);
    },
  );

  const body = vi.mocked(updateUser).mock.calls[0]?.[1];

  expect(Object.keys(body ?? {}).sort()).toEqual(['firstName', 'lastName', 'userType']);
  expect(body).toEqual({
    firstName: SAVED_ROW.firstName,
    lastName: STORED_ROW.lastName,
    userType: STORED_ROW.userType,
  });
}

/**
 * Registers the four cases.
 * @returns {void} Nothing.
 */
function credentialControlCases(): void {
  afterEach(resetIdentityMocks);

  it('paints no credential control', paintsNoCredentialControl);
  it('paints exactly the four controls it declares', paintsFourLabelledControls);
  it(
    'refuses a blank first name with no credential arm ahead of it',
    refusesTheFirstNameWithoutACredentialArm,
  );
  it('sends the three contract members and no credential', sendsThreeMembersOnly);
}

describe('the update screen paints no credential control and sends none', credentialControlCases);
