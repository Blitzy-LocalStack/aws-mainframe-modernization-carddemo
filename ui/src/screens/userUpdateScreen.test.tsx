/**
 * @file Proves the user update screen accepts no credential and submits exactly the three members the
 * published contract declares -- registered divergence D-10 -- and that the three user administration
 * screens preserve the reference's caller-origin transfer.
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
 * The second concern, and why it is in this file
 * ----------------------------------------------
 * ⚠️ Purpose: `app/cbl/COUSR00C.cbl` L192-L207 moves its own program name into `CDEMO-FROM-PROGRAM`
 * immediately before it transfers to `COUSR02C` or `COUSR03C`, and both of those programs prefer that
 * field over their hard-coded menu destination on their back key -- L113-L118 and L111-L118 respectively.
 * The migrated transfer carried the identifier alone, so the preferred arm could never be taken and an
 * administrator who opened a row from the browse was returned to the administrative menu, losing their
 * position in the list on every row they touched. The second block below covers the whole round trip:
 * what the browse hands over, what each receiving screen does with it, and the one key on each screen
 * whose destination is unconditional.
 *
 * Assumptions: the caller-origin cases live beside the credential cases rather than in a file of their
 * own because they render the SAME screen under the same transport mock, so a second file would duplicate
 * the mock factory and the legend lookup and would then be free to drift from this one.
 *
 * Refactoring Rationale: every callback is a named declaration rather than an inline arrow, for the two
 * reasons the sibling screen tests record -- the lint rule requires a documentation block on a function
 * expression in any position, and Prettier detaches a block comment that follows an argument comma.
 */

import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router';

import { AppShell } from '../layout/AppShell';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { deleteUser, getUser, listUsers, updateUser } from '../api/auth';
import type { UserResponse } from '../api/auth';
import type { PageResponse, UserSummary } from '../api/types';
import { PF_KEY_BAR_REGION_LABEL } from '../layout/PfKeyBar';
import {
  ADMIN_MENU_ROUTE,
  USER_LIST_ROUTE,
  navigateSafely,
  screenTransitionState,
} from '../routes/navigation';
import { SHARED_MESSAGES } from '../messages/messages';
import { fieldErrorId, fieldHintId } from '../layout/fieldHelp';
import {
  USER_UPDATE_FIELD_HINTS,
  USER_UPDATE_FIELD_LABELS,
  USER_UPDATE_KEY_LABELS,
  UserUpdateScreen,
} from './userUpdate';
import { USER_DELETE_KEY_LABELS } from '../messages/messages';
import { UserDeleteScreen } from './userDelete';
import {
  USER_LIST_KEY_LABELS,
  USER_LIST_LABELS,
  USER_LIST_ROW_ACTION_CODES,
  userDeletePath,
  userEditPath,
} from './userList';
import UserListScreen from './userList';

/**
 * Builds the mocked surface of the auth transport module.
 *
 * Assumptions: a hoisted function DECLARATION, not an inline factory held in a `const`. Vitest lifts
 * every `vi.mock` call above the imports, so a `const` factory would be in its temporal dead zone at
 * registration time.
 * @returns {Record<string, unknown>} Every transport function the three administration screens in this
 *   file call, plus the width constant all three import from the same module, which a bare spy set would
 *   leave undefined.
 */
function mockAuthTransportModule(): Record<string, unknown> {
  return {
    USER_ID_MAX_LENGTH: 8,
    getUser: vi.fn(),
    updateUser: vi.fn(),
    /*
     * WHY : Assumptions: the browse and the deletion operations are declared here even though the
     *       credential cases never call them, because the caller-origin cases below render the user browse
     *       and the deletion screen -- the two ends of the transfer under test -- and a factory replaces
     *       the module WHOLESALE. A member the factory omits is `undefined` at the call site, which
     *       surfaces as a screen failing to render rather than as the transfer being wrong.
     */
    listUsers: vi.fn(),
    deleteUser: vi.fn(),
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

/**
 * The route this screen is mounted at, whose parameter supplies the administered identifier.
 *
 * Assumptions: this is spelled exactly as `USER_UPDATE_PATH` in `ui/src/router.tsx` -- the ONE route
 * `COUSR02C` holds. A selector-free `'/users/edit'` was declared beside it and is withdrawn, because
 * one program with two routes made the route table publish twenty-two paths for twenty-one programs
 * and stop being a bijection with the reference option tables.
 *
 * Assumptions: the screen's selector-free FIRST TURN survives that withdrawal and is still covered
 * here, because it is a property of the screen rather than of the table -- `app/cbl/COUSR02C.cbl`
 * L99-L104 reads the selection carrier only when it is present. {@link renderScreen} reaches it
 * through a second HARNESS-local mount at {@link UNSELECTED_PATH}; that mount is deliberately not a
 * product route, and `ui/src/routes/routeCensus.test.ts` holds the table itself to twenty-one.
 */
const ROUTE = '/users/:id/edit';

/** The selector-free arrival, which is the path the administrative menu's option 3 navigates to. */
const UNSELECTED_PATH = '/users/edit';

/** An administered path whose identifier is blank once decoded, standing for a malformed link. */
const BLANK_ID_PATH = '/users/%20/edit';

/** Handle on the control that changes route, rendered outside the screen on purpose. */
const NAVIGATE_TEST_ID = 'navigate-to-blank-identifier';

/**
 * A control that moves to the blank-identifier path, rendered inside the router and OUTSIDE the screen.
 *
 * Assumptions: outside the screen, because the screen disables every one of its own controls while a read
 * is in flight -- which is the state this navigation has to happen in. An operator reaches the same state
 * through the address bar or a stale link, neither of which the screen renders.
 * @returns {ReactElement} A button that navigates to {@link BLANK_ID_PATH}.
 */
function NavigateToABlankIdentifier(): ReactElement {
  const navigate = useNavigate();

  /**
   * Changes route to the blank-identifier path.
   *
   * Assumptions: the transition goes through the application's own helper, because
   * `ui/eslint.config.js` refuses a discarded promise even behind `void`.
   * @returns {void} Nothing; the router renders the same screen under a blank identifier.
   */
  function goToTheBlankIdentifier(): void {
    navigateSafely(navigate, BLANK_ID_PATH);
  }

  return (
    <button type="button" data-testid={NAVIGATE_TEST_ID} onClick={goToTheBlankIdentifier}>
      {BLANK_ID_PATH}
    </button>
  );
}

/**
 * Renders the screen at a concrete administration path.
 *
 * Assumptions: the entry is a parameter with the selected arrival as its default, so every existing
 * case keeps arriving with an identifier while the selector-free arrival can be exercised without a
 * second harness. TWO patterns are mounted, not one: {@link ROUTE} is the product route and
 * {@link UNSELECTED_PATH} is a harness-local mount for the screen's selector-free first turn, which
 * the product table deliberately no longer offers a route to.
 * @param {string} [entry] - Concrete path to open; the selected arrival by default.
 * @returns {ReactElement} The composed tree under test.
 */
function renderScreen(entry: string = `/users/${USER_ID}/edit`): ReactElement {
  return (
    <MemoryRouter initialEntries={[entry]}>
      <NavigateToABlankIdentifier />
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
          {/*
            WHY : Assumptions: the selector-free arrival is mounted HERE and nowhere in
                  `ui/src/router.tsx`. The screen keeps the empty first turn the reference gives it, so
                  the behaviour must be covered, but giving it a product route is what previously made
                  `COUSR02C` hold two of the table's rows. A harness mount exercises the screen without
                  re-adding the row the route census counts.
          */}
          <Route path={UNSELECTED_PATH} element={<UserUpdateScreen />} />
        </Routes>
      </AppShell>
    </MemoryRouter>
  );
}

/** Restores the spies between cases. */
function resetSpies(): void {
  vi.mocked(getUser).mockReset();
  vi.mocked(updateUser).mockReset();
  vi.mocked(listUsers).mockReset();
  vi.mocked(deleteUser).mockReset();
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

/**
 * Builds a read the case settles itself, so one can be held in flight across a route change.
 * @returns {{ promise: Promise<UserResponse>; settle: () => void }} The held read and its answer.
 */
function heldRead(): { readonly promise: Promise<UserResponse>; readonly settle: () => void } {
  /**
   * Stands in until the promise's executor has run, so the binding is never read unset.
   * @returns {never} Never returns; a call means the case settled before the read was armed.
   * @throws {Error} Always, for that reason.
   */
  function notYetArmed(): never {
    throw new Error('the held read was settled before it was armed');
  }

  let deliver: (row: UserResponse) => void = notYetArmed;
  const promise = new Promise<UserResponse>(
    /**
     * Captures the answering route without taking it.
     * @param {(row: UserResponse) => void} resolve - Answers the held read.
     * @returns {void} Nothing; the read is held until the case answers it.
     */
    function holdItOpen(resolve): void {
      deliver = resolve;
    },
  );

  return {
    promise,
    /**
     * Answers the held read with the administered row.
     * @returns {void} Nothing; the awaiting caller resumes.
     */
    settle(): void {
      deliver(STORED);
    },
  };
}

/**
 * A blank identifier refused while a read is outstanding leaves the screen usable.
 *
 * ⚠️ Purpose: the refusal opens a TURN -- deliberately, so an outstanding read cannot seed the
 * form beneath a sentence saying the identifier is empty -- and then issues no request, so it never set
 * the in-flight flag itself. Meanwhile the read it superseded returns without touching that flag, because
 * a superseded answer must not re-enable keys while a newer request is running. With neither clearing it,
 * the screen was left with every control and every key inert and no way out but the clear key.
 *
 * Assumptions: the blank identifier arrives by a ROUTE CHANGE and not by typing, because every control is
 * disabled while the read is in flight -- so typing is the one way the state cannot be reached. A
 * malformed or stale link is how an operator reaches it, and the screen's own blank refusal exists for
 * exactly that arrival: `app/cbl/COUSR02C.cbl` L146-L151 raises the sentence and never reads.
 *
 * Assumptions: the verdict is that a control is ENABLED, which is the observable form of the flag. The
 * sentence is asserted too, so the case cannot pass by failing to refuse at all.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function staysUsableAfterABlankIdentifierRefusesAnOutstandingRead(): Promise<void> {
  const operator = userEvent.setup();
  const held = heldRead();

  vi.mocked(getUser).mockReturnValue(held.promise);
  render(renderScreen());

  await waitFor(
    /**
     * Waits for the mount read to have been dispatched, which is what sets the flag.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(getUser).toHaveBeenCalledWith(USER_ID);
    },
  );
  expect(
    screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName),
    'the controls must be disabled while the read is in flight, or the case arranges nothing',
  ).toBeDisabled();

  await operator.click(screen.getByTestId(NAVIGATE_TEST_ID));
  held.settle();

  /*
   * Assumptions: the sentence is queried with the ALL form because the screen renders it twice -- once
   * in the message band and once beneath the identifier control it names -- so a single-element query
   * would fail on the duplicate rather than on the behaviour under test.
   */
  expect(await screen.findAllByText(SHARED_MESSAGES.USER_ID_CAN_NOT_BE_EMPTY)).not.toHaveLength(0);
  await waitFor(
    /**
     * Waits for the screen to become operable again.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName)).toBeEnabled();
    },
  );
  expect(
    screen.getByLabelText(collapse(USER_UPDATE_FIELD_LABELS.userType)),
    'every control shares the one flag, so none of them may be left inert',
  ).toBeEnabled();
}

/**
 * A refused control names its refusal to assistive technology, and its hint as well.
 *
 * ⚠️ Purpose: the label was associated with its control and the other two texts were not. The
 * refusal went to `Form.Item` as a bare string and the width hint to `extra`, so the design system
 * rendered both in containers nothing referenced: the control carried no `aria-invalid` and no
 * `aria-describedby`, leaving an operator using a screen reader with a field that is marked in colour
 * only. The band shows the same sentence for whichever of the four controls failed, so the association is
 * the only thing that says WHICH.
 *
 * Assumptions: the described identifiers are followed to the elements they name rather than merely
 * asserted present, because the failure being closed is a reference that resolves to nothing -- which
 * some assistive technologies announce as silence and others skip, reading exactly like the defect.
 *
 * Assumptions: the user type is the control under test because it is the one carrying BOTH -- the
 * mapset's one hint, `(A=Admin, U=User)` at `app/bms/COUSR02.bms` L150-L154, sits beside it -- so one
 * case covers the refusal association and the hint association together, in the order the design system
 * renders them.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function describesARefusedControlAndItsHint(): Promise<void> {
  const operator = userEvent.setup();
  await renderWithTheRowFetched();
  const beforeTheRefusal = screen.getByLabelText(collapse(USER_UPDATE_FIELD_LABELS.userType));
  /*
   * Assumptions: the control's own identifier is captured and the ELEMENT is re-read after the refusal,
   * because the design system replaces the child element when its item takes on an error state -- the
   * handle held across the turn is detached, and every attribute read from it is the pre-refusal value.
   * A case that kept the handle would report the defect as present no matter what the screen renders.
   * The identifier itself is stable: it comes from one `useId` call made when the screen mounted.
   */
  const controlId = beforeTheRefusal.id;

  expect(beforeTheRefusal.getAttribute('aria-describedby')).toBe(fieldHintId(controlId));
  expect(document.getElementById(fieldHintId(controlId))).toHaveTextContent(
    USER_UPDATE_FIELD_HINTS.userType,
  );
  expect(beforeTheRefusal).not.toHaveAttribute('aria-invalid');

  await operator.clear(beforeTheRefusal);
  await operator.click(legendControl(USER_UPDATE_KEY_LABELS.PFK05));
  await screen.findAllByText(SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY);

  const userType = screen.getByLabelText(collapse(USER_UPDATE_FIELD_LABELS.userType));
  expect(userType.id, 'the re-read control must be the same one, or the case moved target').toBe(
    controlId,
  );
  expect(userType).toHaveAttribute('aria-invalid', 'true');
  expect(
    userType.getAttribute('aria-describedby'),
    'the refusal is described before the hint, in the order the design system renders them',
  ).toBe(`${fieldErrorId(controlId)} ${fieldHintId(controlId)}`);
  expect(document.getElementById(fieldErrorId(controlId))).toHaveTextContent(
    SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY,
  );
  expect(
    screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName),
    'a control the cascade did not refuse must not be marked',
  ).not.toHaveAttribute('aria-invalid');
}

/**
 * The selector-free arrival prompts for an identifier and reads nothing.
 *
 * Purpose: this is the arrival administrative option 3 performs, and it is the turn a parameterised
 * entry cannot reach. `ui/src/router.tsx` declares exactly ONE route for this program and no
 * selector-free path, so the arrival is staged by {@link renderScreen}'s harness mount; what is under
 * test is what the SCREEN does when the route names nobody, which the withdrawal did not change.
 *
 * Assumptions: the verdict is that no read was dispatched AND that the form is operable. Either alone
 * would pass against the wrong screen -- a screen that read a blank identifier would also leave the
 * controls enabled once the refusal returned, and a screen stuck in flight would also have dispatched
 * nothing if the identifier never reached it. `app/cbl/COUSR02C.cbl` L99-L104 is the behaviour being
 * matched: the carrier is tested against `SPACES AND LOW-VALUES` and the screen otherwise waits.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function promptsWithNoIdentifierInTheRoute(): Promise<void> {
  render(renderScreen(UNSELECTED_PATH));

  const identifier = await screen.findByLabelText(USER_UPDATE_FIELD_LABELS.userId);
  expect(identifier).toHaveValue('');
  expect(identifier).toBeEnabled();
  expect(getUser, 'a route naming no operator must not read one').not.toHaveBeenCalled();
  expect(
    screen.getByLabelText(USER_UPDATE_FIELD_LABELS.firstName),
    'the form must be operable, because nothing is in flight',
  ).toBeEnabled();
}

/** Registers the credential-removal cases. */
function credentialRemovalCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it('prompts for an identifier when the route names none', promptsWithNoIdentifierInTheRoute);
  it('renders no credential control in any form', rendersNoCredentialControl);
  it('submits exactly the three published members', submitsExactlyThePublishedThreeMembers);
  it(
    'still refuses a blank user type and never a credential',
    refusesTheUserTypeAndNeverTheCredential,
  );
  it(
    'stays usable after a blank identifier refuses an outstanding read',
    staysUsableAfterABlankIdentifierRefusesAnOutstandingRead,
  );
  it('describes a refused control and its hint', describesARefusedControlAndItsHint);
}

describe('the user update screen accepts no credential (D-10)', credentialRemovalCases);

/** Marker a probe route paints so an arrival can be observed by text. */
const ARRIVED = 'ARRIVED';

/** Marker a probe route paints when it received no caller origin at all. */
const NO_ORIGIN = 'NO-ORIGIN';

/**
 * A probe route that reports both where it was reached and the caller origin it was handed.
 *
 * Purpose: the origin is the property under test and it travels in router STATE, which leaves no trace
 * in the path -- so a probe that reported only its arrival would pass for a transfer that dropped the
 * origin entirely. Reading `useLocation().state` inside the destination is the only place the handover
 * can be observed as the receiving screen would observe it.
 *
 * Assumptions: the state is read through the application's OWN reader rather than destructured here,
 * because `useLocation().state` is untyped -- a hand-edited history entry can carry anything -- and
 * `screenTransitionState` is the structural check every receiving screen applies to it. Reading it the
 * same way means this probe observes exactly what a real destination observes, and it is deliberately the
 * UNVALIDATED member: whether the origin is admissible is the receiving screen's decision, which the
 * later cases assert by where each key lands.
 * @returns {ReactElement} A line naming the arrival path and the origin it carried.
 */
function ArrivalProbe(): ReactElement {
  const location = useLocation();
  const origin = screenTransitionState(location.state).from ?? NO_ORIGIN;

  return <div>{`${ARRIVED} ${location.pathname} ${origin}`}</div>;
}

/** Route the deletion screen is mounted at, whose parameter supplies the administered identifier. */
const DELETE_ROUTE = '/users/:id/delete';

/** The single page the browse answers with, so one marked row is unambiguous. */
const ONE_ROW_PAGE: PageResponse<UserSummary> = {
  items: [
    { userId: USER_ID, firstName: STORED.firstName, lastName: STORED.lastName, userType: 'U' },
  ],
  firstKey: USER_ID,
  lastKey: USER_ID,
  hasNext: false,
};

/**
 * Renders the user browse with probe routes at both of the per-record destinations it transfers to.
 *
 * Assumptions: the two destinations are PROBES rather than the real screens, because what is under test
 * is what the browse hands over -- the receiving screens' own use of it is asserted separately below. A
 * real screen would additionally issue its own read, so a failure there would present as a failure here.
 * @returns {ReactElement} The composed tree under test.
 */
function renderBrowse(): ReactElement {
  return (
    <MemoryRouter initialEntries={[USER_LIST_ROUTE]}>
      <AppShell>
        <Routes>
          <Route path={USER_LIST_ROUTE} element={<UserListScreen />} />
          <Route path={ROUTE} element={<ArrivalProbe />} />
          <Route path={DELETE_ROUTE} element={<ArrivalProbe />} />
        </Routes>
      </AppShell>
    </MemoryRouter>
  );
}

/**
 * Renders one administration screen at its per-record path, optionally carrying a caller origin.
 *
 * Assumptions: the origin is supplied through the router's own `state` member of an initial entry, which
 * is exactly the channel `navigateSafely` writes it to -- so a case that arranges it this way arranges
 * the same thing the browse produces, rather than a shape only the test can create.
 * @param {ReactElement} screenUnderTest - The screen to mount at the per-record path.
 * @param {string} path - Route pattern the screen is mounted at.
 * @param {string | undefined} from - Caller origin to hand over, or nothing to model a direct arrival.
 * @returns {ReactElement} The composed tree under test.
 */
function renderAtPerRecordPath(
  screenUnderTest: ReactElement,
  path: string,
  from: string | undefined,
): ReactElement {
  const concrete = path === ROUTE ? userEditPath(USER_ID) : userDeletePath(USER_ID);

  return (
    <MemoryRouter
      initialEntries={[from === undefined ? concrete : { pathname: concrete, state: { from } }]}
    >
      <AppShell>
        <Routes>
          <Route path={path} element={screenUnderTest} />
          <Route path={USER_LIST_ROUTE} element={<ArrivalProbe />} />
          <Route path={ADMIN_MENU_ROUTE} element={<ArrivalProbe />} />
        </Routes>
      </AppShell>
    </MemoryRouter>
  );
}

/**
 * Marks one browse row with an action code and takes the turn, which is how the reference transfers.
 *
 * Assumptions: the code is typed into the row's own action cell and the turn taken with the ENTER
 * control, because that is the whole of the reference's transfer condition:
 * `app/cbl/COUSR00C.cbl` L149-L184 reduces the ten action cells on the ENTER key alone. Navigating
 * directly to the destination would prove nothing about the browse.
 * @param {string} code - The action code, from the screen's own published pair.
 * @returns {Promise<void>} Resolves once the turn has been taken.
 */
async function markTheRowAndTakeTheTurn(code: string): Promise<void> {
  const operator = userEvent.setup();
  vi.mocked(listUsers).mockResolvedValue(ONE_ROW_PAGE);
  render(renderBrowse());

  const cell = await screen.findByLabelText(`${USER_LIST_LABELS.selColumn.trim()} ${USER_ID}`);
  await operator.type(cell, code);
  await operator.click(legendControl(USER_LIST_KEY_LABELS.ENTER));
}

/**
 * The browse hands the update screen its own route as the caller origin.
 *
 * ⚠️ Purpose: the transfer previously carried the identifier alone, so the receiving screen's PF3 arm
 * could only ever take its fallback -- an administrator who opened a row from the list was returned to
 * the administrative menu and had to re-enter the browse and re-page to reach the next row. The reference
 * does not lose that: `app/cbl/COUSR00C.cbl` L192-L197 moves its own program name into
 * `CDEMO-FROM-PROGRAM` in the same paragraph as the transfer to `COUSR02C`.
 *
 * Assumptions: both the destination path and the origin are asserted in one expectation, because the two
 * are one handover -- a case that checked the path alone would pass for the defect being prevented, and a
 * case that checked the origin alone would pass for a transfer to the wrong screen.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function theBrowseHandsTheUpdateScreenItsOrigin(): Promise<void> {
  await markTheRowAndTakeTheTurn(USER_LIST_ROW_ACTION_CODES.update);

  expect(
    await screen.findByText(`${ARRIVED} ${userEditPath(USER_ID)} ${USER_LIST_ROUTE}`),
  ).toBeInTheDocument();
}

/**
 * The browse hands the deletion screen its own route as the caller origin.
 *
 * Assumptions: this is asserted separately from the update transfer even though one line in the screen
 * produces both, because the two arms are chosen by different action codes and the reference states them
 * as two paragraphs -- `app/cbl/COUSR00C.cbl` L192-L197 and L202-L207 -- so a reduction that dropped one
 * code would leave the other's case green.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function theBrowseHandsTheDeleteScreenItsOrigin(): Promise<void> {
  await markTheRowAndTakeTheTurn(USER_LIST_ROW_ACTION_CODES.delete);

  expect(
    await screen.findByText(`${ARRIVED} ${userDeletePath(USER_ID)} ${USER_LIST_ROUTE}`),
  ).toBeInTheDocument();
}

/**
 * Waits for the mount read to have been dispatched and answered.
 *
 * Assumptions: both screens disable every control while their mount read is in flight, so a key pressed
 * before the row lands is withheld and the case would observe no transfer at all -- a false negative that
 * looks exactly like the origin having been dropped. This is the half of that wait the two screens share.
 * @returns {Promise<void>} Resolves once the read has been issued for the administered identifier.
 */
async function waitForTheReadToBeIssued(): Promise<void> {
  await waitFor(
    /**
     * Waits for the read to have been dispatched.
     * @returns {void} Nothing; the expectation throws until it holds.
     */
    (): void => {
      expect(getUser).toHaveBeenCalledWith(USER_ID);
    },
  );
}

/**
 * Waits for the administered row to reach the update screen's editable controls.
 * @returns {Promise<void>} Resolves once the fetched surname holds a control's value.
 */
async function waitForTheFetchedRowInControls(): Promise<void> {
  await waitForTheReadToBeIssued();
  expect(await screen.findByDisplayValue(STORED.lastName)).toBeInTheDocument();
}

/**
 * Waits for the administered row to reach the deletion screen's protected values.
 *
 * ⚠️ Assumptions: the surname is sought as TEXT here and as a control's value on the update screen,
 * because the two mapsets differ in kind and not merely in wording. `app/bms/COUSR03.bms` paints the
 * three name and type fields with `ASKIP`, so the deletion screen renders them as protected text and
 * offers one enterable control -- its fetch key -- whereas the update screen renders four editable
 * controls. A shared display-value wait failed on the deletion screen for that reason, which reads as the
 * row never arriving rather than as the query being wrong for the screen.
 * @returns {Promise<void>} Resolves once the fetched surname is painted.
 */
async function waitForTheFetchedRowOnTheGlass(): Promise<void> {
  await waitForTheReadToBeIssued();
  expect(await screen.findByText(STORED.lastName)).toBeInTheDocument();
}

/**
 * The update screen's saving exit returns to the browse when the browse sent it.
 *
 * Assumptions: the save is arranged to succeed but its OUTCOME is immaterial -- the reference performs
 * `UPDATE-USER-INFO` and then `RETURN-TO-PREV-SCREEN` with no test between them at
 * `app/cbl/COUSR02C.cbl` L111-L119 -- so what this case fixes is the DESTINATION of a transfer that
 * happens either way.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function theUpdateScreenReturnsToTheBrowseItCameFrom(): Promise<void> {
  const operator = userEvent.setup();
  vi.mocked(getUser).mockResolvedValue(STORED);
  vi.mocked(updateUser).mockResolvedValue(STORED);
  render(renderAtPerRecordPath(<UserUpdateScreen />, ROUTE, USER_LIST_ROUTE));
  await waitForTheFetchedRowInControls();

  await operator.click(legendControl(USER_UPDATE_KEY_LABELS.PFK03));

  expect(await screen.findByText(`${ARRIVED} ${USER_LIST_ROUTE} ${NO_ORIGIN}`)).toBeInTheDocument();
}

/**
 * The update screen's saving exit falls back to the administrative menu with no origin.
 *
 * Assumptions: this is the reference's own blank-field arm -- `app/cbl/COUSR02C.cbl` L113-L118 transfers
 * to `'COADM01C'` when `CDEMO-FROM-PROGRAM` is spaces or low values -- so the fallback is asserted as
 * behaviour rather than tolerated as a default. It is also what an operator reaching the screen by a
 * typed or stale link gets, which is the arrival a forged origin would otherwise redirect.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function theUpdateScreenFallsBackToTheAdministrativeMenu(): Promise<void> {
  const operator = userEvent.setup();
  vi.mocked(getUser).mockResolvedValue(STORED);
  vi.mocked(updateUser).mockResolvedValue(STORED);
  render(renderAtPerRecordPath(<UserUpdateScreen />, ROUTE, undefined));
  await waitForTheFetchedRowInControls();

  await operator.click(legendControl(USER_UPDATE_KEY_LABELS.PFK03));

  expect(
    await screen.findByText(`${ARRIVED} ${ADMIN_MENU_ROUTE} ${NO_ORIGIN}`),
  ).toBeInTheDocument();
}

/**
 * The update screen's cancel key returns to the administrative menu even when the browse sent it.
 *
 * ⚠️ Assumptions: this key is UNCONDITIONAL where the saving exit is not, and the two are asserted in the
 * same file for that reason: `app/cbl/COUSR02C.cbl` L124-L125 moves `'COADM01C'` into
 * `CDEMO-TO-PROGRAM` with no preference for the calling program at all. Resolving the origin on both keys
 * would be a plausible simplification and would be wrong, so the difference is pinned by a case that
 * supplies an origin and expects it to be IGNORED.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function theUpdateScreenCancelsToTheMenuDespiteAnOrigin(): Promise<void> {
  const operator = userEvent.setup();
  vi.mocked(getUser).mockResolvedValue(STORED);
  render(renderAtPerRecordPath(<UserUpdateScreen />, ROUTE, USER_LIST_ROUTE));
  await waitForTheFetchedRowInControls();

  await operator.click(legendControl(USER_UPDATE_KEY_LABELS.PFK12));

  expect(
    await screen.findByText(`${ARRIVED} ${ADMIN_MENU_ROUTE} ${NO_ORIGIN}`),
  ).toBeInTheDocument();
}

/**
 * The deletion screen's back key returns to the browse when the browse sent it.
 *
 * Assumptions: the deletion screen is exercised alongside the update screen rather than trusted to match
 * it, because the two implement the same two-armed decision from two different programs and neither
 * shares a function with the other -- `app/cbl/COUSR03C.cbl` L111-L118 is its own transcription.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function theDeleteScreenReturnsToTheBrowseItCameFrom(): Promise<void> {
  const operator = userEvent.setup();
  vi.mocked(getUser).mockResolvedValue(STORED);
  render(renderAtPerRecordPath(<UserDeleteScreen />, DELETE_ROUTE, USER_LIST_ROUTE));
  await waitForTheFetchedRowOnTheGlass();

  await operator.click(legendControl(USER_DELETE_KEY_LABELS.PFK03));

  expect(await screen.findByText(`${ARRIVED} ${USER_LIST_ROUTE} ${NO_ORIGIN}`)).toBeInTheDocument();
}

/**
 * The deletion screen's back key falls back to the administrative menu with no origin.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function theDeleteScreenFallsBackToTheAdministrativeMenu(): Promise<void> {
  const operator = userEvent.setup();
  vi.mocked(getUser).mockResolvedValue(STORED);
  render(renderAtPerRecordPath(<UserDeleteScreen />, DELETE_ROUTE, undefined));
  await waitForTheFetchedRowOnTheGlass();

  await operator.click(legendControl(USER_DELETE_KEY_LABELS.PFK03));

  expect(
    await screen.findByText(`${ARRIVED} ${ADMIN_MENU_ROUTE} ${NO_ORIGIN}`),
  ).toBeInTheDocument();
}

/**
 * The deletion screen's cancel key returns to the administrative menu even when the browse sent it.
 *
 * Assumptions: the key is invoked from the KEYBOARD rather than from the legend, because this screen
 * publishes no label for it -- `app/bms/COUSR03.bms` paints four keys on row 24 and PF12 is not among
 * them -- so `usePfKeys` binds it without rendering a control. `app/cbl/COUSR03C.cbl` L121-L122 makes
 * it unconditional all the same.
 * @returns {Promise<void>} Resolves once the arrival has been observed.
 */
async function theDeleteScreenCancelsToTheMenuDespiteAnOrigin(): Promise<void> {
  const operator = userEvent.setup();
  vi.mocked(getUser).mockResolvedValue(STORED);
  render(renderAtPerRecordPath(<UserDeleteScreen />, DELETE_ROUTE, USER_LIST_ROUTE));
  await waitForTheFetchedRowOnTheGlass();

  await operator.keyboard('{F12}');

  expect(
    await screen.findByText(`${ARRIVED} ${ADMIN_MENU_ROUTE} ${NO_ORIGIN}`),
  ).toBeInTheDocument();
}

/** Registers the caller-origin cases. */
function callerOriginCases(): void {
  beforeEach(resetSpies);
  afterEach(resetSpies);

  it('hands the update screen the browse as its origin', theBrowseHandsTheUpdateScreenItsOrigin);
  it('hands the delete screen the browse as its origin', theBrowseHandsTheDeleteScreenItsOrigin);
  it('returns from the update screen to the browse', theUpdateScreenReturnsToTheBrowseItCameFrom);
  it(
    'returns from the update screen to the menu with no origin',
    theUpdateScreenFallsBackToTheAdministrativeMenu,
  );
  it(
    'cancels from the update screen to the menu despite an origin',
    theUpdateScreenCancelsToTheMenuDespiteAnOrigin,
  );
  it('returns from the delete screen to the browse', theDeleteScreenReturnsToTheBrowseItCameFrom);
  it(
    'returns from the delete screen to the menu with no origin',
    theDeleteScreenFallsBackToTheAdministrativeMenu,
  );
  it(
    'cancels from the delete screen to the menu despite an origin',
    theDeleteScreenCancelsToTheMenuDespiteAnOrigin,
  );
}

describe('the user screens return to the caller that sent them', callerOriginCases);
