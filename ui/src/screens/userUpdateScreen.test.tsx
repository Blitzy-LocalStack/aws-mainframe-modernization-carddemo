/**
 * @file Proves the user update screen accepts no credential and submits exactly the three members the
 * published contract declares, which is registered divergence D-10.
 *
 * Purpose
 * -------
 * `ui/src/screens/userUpdate/index.tsx` replaces `app/cbl/COUSR02C.cbl`. That program paints a
 * credential control on row 13 of `app/bms/COUSR02.bms` (L125-L134), pre-fills it from the stored
 * plaintext value at L169, refuses a blank one at L198-L203 and compares it at L227-L230. None of the
 * four has a target: AAP section 0.7.8 declines parity on identity, `auth.users` carries no credential
 * column, and `UpdateUserRequest` seals itself with `additionalProperties: false` over three properties.
 *
 * What these cases exist to prevent
 * ---------------------------------
 * ⚠️ Assumptions: an earlier shape of this screen RENDERED the control and its blank refusal while
 * omitting the value from the request, so an administrator typed a credential into a control whose value
 * was discarded -- and every affordance a form has told them it had been set. These cases are what makes
 * the removal permanent rather than a state the file happens to be in: one asserts that nothing on the
 * screen accepts a credential, and one asserts the request carries exactly the published three members.
 * A case asserting only the request would pass against a screen that collected one and threw it away,
 * which is precisely the defect.
 *
 * Assumptions: the transport module is mocked so each case controls what the service answers and can
 * assert what the screen sent. The verbatim sentences are read from the catalog rather than retyped, for
 * the reason the sibling screen tests record.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the sibling screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router';

import { AppShell } from '../layout/AppShell';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getUser, updateUser } from '../api/auth';
import type { UserResponse } from '../api/auth';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import { SHARED_MESSAGES } from '../messages/messages';
import { USER_UPDATE_FIELD_LABELS, USER_UPDATE_KEY_LABELS, UserUpdateScreen } from './userUpdate';

/**
 * Builds the mocked surface of the auth transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} The two transport functions this screen calls plus the width
 *   constant it imports from the same module, which a bare spy pair would leave undefined.
 */
function mockAuthTransportModule(): Record<string, unknown> {
  return {
    USER_ID_MAX_LENGTH: 8,
    getUser: vi.fn(),
    updateUser: vi.fn(),
  };
}

vi.mock('../api/auth', mockAuthTransportModule);

/** The identifier the administered row carries, at its declared eight-character width. */
const USER_ID = 'USER0001';

/** The row the read answers with, at the widths the mapset and the copybook agree on. */
const STORED: UserResponse = {
  userId: USER_ID,
  firstName: 'ADA',
  lastName: 'LOVELACE',
  userType: 'U',
  cognitoSub: '00000000-0000-4000-8000-000000000001',
};

/** The route this screen is mounted at, whose parameter supplies the administered identifier. */
const ROUTE = '/users/:id/edit';

/**
 * Renders the screen at a concrete administration path.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(): ReactElement {
  return (
    <MemoryRouter initialEntries={[`/users/${USER_ID}/edit`]}>
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
          <Route path={ROUTE} element={<UserUpdateScreen />} />
        </Routes>
      </AppShell>
    </MemoryRouter>
  );
}

/** Restores the spies between cases. */
function resetSpies(): void {
  vi.mocked(getUser).mockReset();
  vi.mocked(updateUser).mockReset();
}

/**
 * Normalises a fixed-width source value the way the testing library normalises DOM text.
 *
 * Assumptions: this is needed for the user-type label specifically. The mapset declares
 * `INITIAL='User Type: '` at `LENGTH=11` where the visible text is ten characters, so the eleventh is a
 * space the terminal painted and the screen renders verbatim under Transformation Rule T8. The DOM
 * collapses it on display, so the expectation is collapsed too rather than the label being trimmed at its
 * declaration, which would make the test the source of truth instead of the mapset.
 * @param {string} value - The source value, possibly padded.
 * @returns {string} The value with interior runs collapsed and the ends trimmed.
 */
function collapse(value: string): string {
  return value.replace(/\s+/gu, ' ').trim();
}

/**
 * Returns the legend control that invokes one function key.
 *
 * Assumptions: the control is looked up INSIDE the function-key region rather than by label alone,
 * because `PfKeyBar` renders a `nav` with an accessible name -- role `navigation`, not `region` -- and a
 * global label lookup would break the moment a screen label collided with a key label.
 * @param {string} label - The legend label, verbatim from the mapset's row-24 literal.
 * @returns {HTMLElement} The legend control bearing that label.
 * @throws {Error} If the region holds no control with that label, so a renamed label fails loudly rather
 *   than silently exercising nothing.
 */
function legendControl(label: string): HTMLElement {
  const region = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
  const found = Array.from(region.querySelectorAll('button')).find(
    /**
     * Reports whether one control's text is the label sought, with interior runs collapsed.
     * @param {HTMLButtonElement} control - Candidate control.
     * @returns {boolean} Whether its text matches.
     */
    (control: HTMLButtonElement): boolean =>
      (control.textContent ?? '').replace(/\s+/gu, ' ').trim() === label,
  );
  if (found === undefined) {
    throw new Error(`no function-key control labelled ${label}`);
  }
  return found;
}

/**
 * Renders the screen with the read already arranged and waits for the row to reach the glass.
 *
 * Assumptions: the read is arranged BEFORE the render rather than before a key press, because this screen
 * fetches on mount from the route parameter -- `app/cbl/COUSR02C.cbl` reaches `READ-USER-SEC-FILE` at L163
 * from the arm that receives the selected identifier, and the browser's equivalent of that arrival is the
 * mount. Arranging afterwards leaves the mount's own call unstubbed.
 * @returns {Promise<void>} Resolves once the first name control holds the stored value.
 */
async function renderWithTheRowFetched(): Promise<void> {
  vi.mocked(getUser).mockResolvedValue(STORED);
  render(renderScreen());
  await waitFor(
    /**
     * Waits for the fetched first name to be seeded into its control.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName)).toHaveValue(
        STORED.firstName,
      );
    },
  );
}

/**
 * Proves no control on this screen accepts a credential, in any of the three ways one could appear.
 *
 * ⚠️ Assumptions: three independent absences are asserted rather than one, because each is a different
 * way the control could come back. The mapset's own label would reappear if the field roster regained its
 * entry; a `type="password"` input would appear if the presentation regained its non-display branch; and
 * the `(8 Char)` hint would appear if the hint table regained its entry. Asserting only the label would
 * pass against an unlabelled masked input, which is a worse state than the one being prevented.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function rendersNoCredentialControl(): Promise<void> {
  await renderWithTheRowFetched();

  expect(screen.queryByLabelText('Password:')).not.toBeInTheDocument();
  expect(screen.queryByText('(8 Char)')).not.toBeInTheDocument();
  expect(document.querySelectorAll('input[type="password"]')).toHaveLength(0);
}

/**
 * Proves the save submits exactly the three published members and no fourth.
 *
 * Assumptions: the body is compared for EXACT equality rather than by member, because the property this
 * case holds is an ABSENCE. A member-wise check would pass against a body that also carried a credential,
 * which is the shape the published contract refuses outright with `additionalProperties: false`.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function submitsExactlyThePublishedThreeMembers(): Promise<void> {
  const operator = userEvent.setup();
  await renderWithTheRowFetched();

  vi.mocked(updateUser).mockResolvedValue({ ...STORED, firstName: 'GRACE' });
  const firstName = screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName);
  await operator.clear(firstName);
  await operator.type(firstName, 'GRACE');
  await operator.click(legendControl(USER_UPDATE_KEY_LABELS.PFK05));

  await waitFor(
    /**
     * Waits for the write to have been dispatched.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(updateUser).toHaveBeenCalledTimes(1);
    },
  );

  expect(vi.mocked(updateUser).mock.calls[0]?.[1]).toEqual({
    firstName: 'GRACE',
    lastName: STORED.lastName,
    userType: STORED.userType,
  });
}

/**
 * Proves the credential's blank refusal is gone while the other four still fire.
 *
 * ⚠️ Assumptions: the removed sentence is asserted absent AND a surviving one asserted present, in one
 * case, because the two together are what distinguishes a removed arm from a broken cascade. Dropping the
 * fourth arm of an ordered `EVALUATE TRUE` must leave the fifth reachable, and the fifth is the user
 * type's -- so clearing it must still raise `User Type can NOT be empty...` and must never raise
 * `Password can NOT be empty...`, which nothing on this screen can now produce.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function refusesTheUserTypeAndNeverTheCredential(): Promise<void> {
  const operator = userEvent.setup();
  await renderWithTheRowFetched();

  await operator.clear(screen.getByLabelText(collapse(USER_UPDATE_FIELD_LABELS.userType)));
  await operator.click(legendControl(USER_UPDATE_KEY_LABELS.PFK05));

  /*
   * Assumptions: the sentence is expected TWICE, because the reference moves one string into
   * `WS-MESSAGE` and also highlights the field it names, so the migrated screen renders it in the message
   * band and again beneath the control. A single-element query would fail on the duplicate rather than on
   * the behaviour.
   */
  expect(await screen.findAllByText(SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY)).toHaveLength(2);
  expect(screen.queryByText(SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY)).not.toBeInTheDocument();
  expect(updateUser).not.toHaveBeenCalled();
}

/** Registers the credential-removal cases. */
function credentialRemovalCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it('renders no credential control in any form', rendersNoCredentialControl);
  it('submits exactly the three published members', submitsExactlyThePublishedThreeMembers);
  it(
    'still refuses a blank user type and never a credential',
    refusesTheUserTypeAndNeverTheCredential,
  );
}

describe('the user update screen accepts no credential (D-10)', credentialRemovalCases);
