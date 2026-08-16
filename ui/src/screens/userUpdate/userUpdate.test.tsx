/**
 * @file Component tests for the user-update screen's credential contract and turn sequencing.
 *
 * Purpose
 * -------
 * Cover the two behaviours a review found defective on this screen, both of which are invisible in the
 * type system and one of which is invisible in any single-turn test:
 *
 * - the credential control is GONE, and it is gone completely. The screen must paint no credential
 *   control, must not demand one before a save, and must send a body of exactly the three members the
 *   published update contract declares -- so that no success sentence it renders can be read as a
 *   credential having been changed. The divergence this closes is registered as
 *   `D-USER-UPDATE-NO-CREDENTIAL-CONTROL` in `docs/architecture/cobol-to-service-traceability.md`.
 * - no outcome of a superseded turn may reach the screen. A read still on the wire must not seed the
 *   form after the operator has cleared it or pointed the route at a different user, a write still on
 *   the wire must not claim success over an emptied screen, and the three keys that OPEN a turn must
 *   stand down while one is outstanding -- including PF3, whose own legend promises `F3=Save&&Exit` and
 *   which previously exited WITHOUT saving when it was pressed during another turn.
 *
 * How the races are driven
 * ------------------------
 * Assumptions: the transport is substituted with DEFERRED promises the case resolves by hand. A race is
 * an ORDERING, so the ordering has to be what the test controls: a case starts a turn, performs the
 * action that should supersede it, and only then releases the response. Resolving first and asserting
 * afterwards would pass against the defective code, because the defect is not that the response is
 * wrong -- it is that a correct response for a superseded question is applied.
 *
 * Why the screen is mounted inside the shell
 * -----------------------------------------
 * Assumptions: `ui/src/layout/AppShell.tsx` is the authenticated layout route and this screen delegates
 * its title band and its key legend to it, so rendering the screen alone would leave the legend buttons
 * absent -- and the legend is where the stood-down keys are observed. The shell is mounted for the same
 * reason `ui/src/screens/accountUpdate/accountUpdateTurns.test.tsx` mounts it.
 *
 * Why the transport module is spread rather than replaced
 * ------------------------------------------------------
 * Assumptions: the factory returns the REAL module with two members overridden, following
 * `ui/src/screens/signon/signon.test.tsx`. This screen reads `USER_ID_MAX_LENGTH` from the same module
 * to size its identifier control, and `ui/src/hooks/useAuth.ts` -- which the shell mounts -- reads three
 * further members from it. A bare replacement would leave the identifier control sized `undefined` and
 * the shell's identity hook holding undefined operations, so the failures would be about the substitution
 * rather than about the screen.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/vitest.config.ts sets `globals: false` and records that as a contract.
import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter, Route, Routes } from 'react-router';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type * as AuthModule from '../../api/auth';
import type { UpdateUserRequest, UserResponse } from '../../api/types';

const getUserMock = vi.fn();
const updateUserMock = vi.fn();

vi.mock(
  '../../api/auth',
  /**
   * Replaces the two operations this screen issues while leaving every other export intact.
   * @returns {Promise<typeof AuthModule>} The real module with the read and the write stubbed.
   */
  async () => {
    const actual = await vi.importActual<typeof AuthModule>('../../api/auth');
    return {
      ...actual,
      /**
       * Stands in for the keyed user read.
       * @returns {Promise<unknown>} Whatever the case configured.
       */
      getUser: getUserMock,
      /**
       * Stands in for the user update.
       * @returns {Promise<unknown>} Whatever the case configured.
       */
      updateUser: updateUserMock,
    };
  },
);

// Assumptions: the screen and its label records are imported DYNAMICALLY, below the mock declaration,
//   for the reason `signon.test.tsx` records: a static import is hoisted above the factory, which
//   closes over the two spies, so the eager form fails on a temporal-dead-zone access before any case
//   runs.
const {
  UserUpdateScreen,
  USER_UPDATE_FIELD_LABELS,
  USER_UPDATE_FIELD_HINTS,
  USER_UPDATE_FIELD_WIDTHS,
  USER_UPDATE_KEY_LABELS,
} = await import('./index');

// Assumptions: the SHELL and its legend's region name are imported dynamically for the same reason, and
//   this one was measured rather than anticipated. `ui/src/layout/AppShell.tsx` imports
//   `ui/src/hooks/useAuth.ts`, which imports the very module substituted above -- so a static import of
//   the shell is hoisted over the factory, runs it, and fails on `Cannot access 'getUserMock' before
//   initialization` before a single case runs. That is the shell's documented dependency edge reaching a
//   test file, and it is why the two layout imports sit below the substitution rather than beside the
//   others.
const { AppShell } = await import('../../layout/AppShell');
const { PF_KEY_BAR_REGION_LABEL } = await import('../../layout/PfKeyBar');
const { MESSAGE_BAND_TEST_ID } = await import('../../layout/MessageBand');

const { INVALID_KEY_PRESSED, PROGRAM_MESSAGES, SHARED_MESSAGES } =
  await import('../../messages/messages');

/** The four controls this screen admits, in the order the reference validates them. */
const ADMITTED_FIELDS = ['userId', 'firstName', 'lastName', 'userType'] as const;

/** Sentences this screen's own program contributes. */
const UPDATE_MESSAGES = PROGRAM_MESSAGES.COUSR02C;

/** The row the first read returns, whose first name is the discriminator in the ordering cases. */
const ALICE: UserResponse = {
  userId: 'AAA00001',
  firstName: 'ALICE',
  lastName: 'ANDREWS',
  userType: 'U',
  cognitoSub: '11111111-1111-1111-1111-111111111111',
};

/** The row the second read returns, distinguishable from the first in every member. */
const BOB: UserResponse = {
  userId: 'BBB00002',
  firstName: 'BOB',
  lastName: 'BUCKLEY',
  userType: 'A',
  cognitoSub: '22222222-2222-2222-2222-222222222222',
};

/** A promise whose resolution and rejection the case controls, for any published shape. */
interface Deferred<T> {
  /** The promise handed to the screen. */
  readonly promise: Promise<T>;
  /** Releases the response. */
  readonly release: (value: T) => void;
  /** Fails the request. */
  readonly refuse: (reason: unknown) => void;
}

/**
 * Creates a promise whose settlement the case controls.
 *
 * Assumptions: `let` bindings use definite assignment rather than a no-op initialiser, because a
 * promise executor runs synchronously so both are assigned before this returns.
 * @returns {Deferred<T>} The promise and the two functions that settle it.
 */
function deferred<T>(): Deferred<T> {
  let release!: (value: T) => void;
  let refuse!: (reason: unknown) => void;
  const promise = new Promise<T>(
    /**
     * Captures both settlement functions.
     * @param {(value: T) => void} resolve - The promise's resolver.
     * @param {(reason: unknown) => void} reject - The promise's rejecter.
     * @returns {void} Nothing.
     */
    (resolve: (value: T) => void, reject: (reason: unknown) => void): void => {
      release = resolve;
      refuse = reject;
    },
  );
  return { promise, release, refuse };
}

/**
 * Drains the microtask queue inside `act` so every state change a release caused is committed.
 * @returns {Promise<void>} Resolves once the queue is empty.
 */
async function settle(): Promise<void> {
  await act(
    /**
     * Yields once so pending promise continuations run.
     * @returns {Promise<void>} Resolves once the queue is empty.
     */
    async (): Promise<void> => {
      await Promise.resolve();
    },
  );
}

/**
 * Renders the screen inside the shell at its own route, optionally beside a link to a second user.
 *
 * Assumptions: the link is placed INSIDE the router but OUTSIDE `Routes`, so clicking it changes the
 * route parameter without unmounting the screen. That is the arrangement the route-change race needs:
 * a remount would discard the outstanding request's closure along with the screen and the case would
 * prove nothing about sequencing.
 * @param {string} initialUserId - Identifier the route names on mount.
 * @param {string | null} linkUserId - Identifier a rendered link navigates to, or `null` for no link.
 * @returns {void} Completion is the mounted tree.
 */
function renderScreen(initialUserId: string, linkUserId: string | null): void {
  render(
    <MemoryRouter initialEntries={[`/users/${initialUserId}/edit`]}>
      {linkUserId === null ? null : (
        <Link to={`/users/${linkUserId}/edit`}>{SWITCH_USER_LABEL}</Link>
      )}
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/users/:id/edit" element={<UserUpdateScreen />} />
          <Route path="/admin" element={<AdminSentinel />} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

/** Text of the link the route-change race clicks. */
const SWITCH_USER_LABEL = 'switch user';

/** Text the administrative-menu route renders, which is how an exit is observed. */
const ADMIN_SENTINEL_TEXT = 'administrative menu reached';

/**
 * Stands in for the administrative menu so a transfer of control is observable.
 * @returns {ReactElement} A paragraph carrying the sentinel text.
 */
function AdminSentinel(): ReactElement {
  return <p>{ADMIN_SENTINEL_TEXT}</p>;
}

/**
 * Reads one of the screen's controls by its label.
 *
 * Assumptions: the control is located by LABEL and not by identifier, because this screen composes its
 * control identifiers with `useId()` -- so no stable identifier exists to query. The label text is taken
 * from the screen's own exported record and TRIMMED, because Testing Library normalises the rendered
 * text before comparing and the user-type label carries a trailing space the mapset painted.
 * @param {(typeof ADMITTED_FIELDS)[number]} field - The control's field name.
 * @returns {HTMLInputElement} That field's control.
 */
function control(field: (typeof ADMITTED_FIELDS)[number]): HTMLInputElement {
  return screen.getByLabelText<HTMLInputElement>(USER_UPDATE_FIELD_LABELS[field].trim());
}

/**
 * Returns the message band this screen paints above its controls.
 *
 * Assumptions: sentences are asserted INSIDE the band rather than anywhere on the screen, and this was
 * measured. A blank refusal is rendered twice -- once in the band and once as the refused control's help
 * text -- so an unscoped `getByText` finds two nodes and throws before it can assert anything. The band
 * is the reference's own row-23 message field, so it is also the faithful place to look.
 * @returns {HTMLElement} The band element.
 */
function messageBand(): HTMLElement {
  return screen.getByTestId(MESSAGE_BAND_TEST_ID);
}

/**
 * Returns the key legend the shell paints for this screen.
 *
 * Assumptions: the legend is located as a NAVIGATION landmark by its own region name, because
 * `ui/src/layout/PfKeyBar.tsx` renders it as a `nav` with `aria-label` rather than as a toolbar, and
 * because the shell paints its own sign-off control outside it. Querying buttons unscoped would find
 * that control too.
 * @returns {HTMLElement} The legend landmark.
 */
function legend(): HTMLElement {
  return screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });
}

/**
 * Returns one legend button by its label.
 * @param {string} label - The button's decoded legend text.
 * @returns {HTMLElement} That button.
 */
function keyButton(label: string): HTMLElement {
  return within(legend()).getByRole('button', { name: label });
}

/**
 * Mounts the screen and completes its route-driven read, leaving the form seeded from one row.
 * @param {ReturnType<typeof userEvent.setup>} _user - The interaction driver, kept for symmetry.
 * @param {UserResponse} row - The row the read returns.
 * @returns {Promise<void>} Resolves once the form carries the row.
 */
async function renderWithOneRowRead(
  _user: ReturnType<typeof userEvent.setup>,
  row: UserResponse,
): Promise<void> {
  getUserMock.mockResolvedValueOnce(row);
  renderScreen(row.userId, null);
  await settle();
}

/**
 * The screen paints no credential control, label or hint anywhere.
 *
 * Assumptions: three independent witnesses are asserted rather than one, because each would survive a
 * different partial removal. A control of password type is what the design system renders for a secret
 * field; the `Password:` caption is what the mapset paints beside it; and the `(8 Char)` hint is what
 * sits after it. Removing the control while leaving either static string would leave a screen captioned
 * for a field it does not have, which is the class of defect this whole review is about.
 *
 * Assumptions: absence is asserted on nodes the design system does NOT animate out -- a control that
 * was never rendered and two literal captions -- so no exit-motion residue can keep a removed node
 * readable. That hazard is real on this component library and is recorded on the sibling screen's
 * marker helper.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function paintsNoCredentialControl(): Promise<void> {
  const user = userEvent.setup();
  await renderWithOneRowRead(user, ALICE);

  expect(document.querySelectorAll('input[type="password"]')).toHaveLength(0);
  expect(document.querySelectorAll('.ant-input-password')).toHaveLength(0);
  expect(screen.queryByText('Password:')).toBeNull();
  expect(screen.queryByText('(8 Char)')).toBeNull();
}

/**
 * The screen's exported records name exactly the four admitted fields, and one hint.
 *
 * Assumptions: the records are asserted by their KEY SETS and not by their contents, because a record
 * that regained a fifth entry is the first thing a restored control would need -- the field renderer
 * reads a label and a width per field, so a credential control cannot reappear without one of these
 * growing. The hint record is asserted separately because it was always sparse: it carried the
 * `(A=Admin, U=User)` domain hint and the credential's `(8 Char)` width hint, and only the first
 * survives.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function namesExactlyTheFourAdmittedFields(): Promise<void> {
  expect(Object.keys(USER_UPDATE_FIELD_LABELS).sort()).toStrictEqual([...ADMITTED_FIELDS].sort());
  expect(Object.keys(USER_UPDATE_FIELD_WIDTHS).sort()).toStrictEqual([...ADMITTED_FIELDS].sort());
  expect(Object.keys(USER_UPDATE_FIELD_HINTS)).toStrictEqual(['userType']);
  await Promise.resolve();
}

/**
 * A save with only the four controls filled reaches the fourth refusal arm, not a fifth.
 *
 * Assumptions: this is a REACHABILITY proof and the sentence it names is the whole assertion. The
 * reference validates five fields in a short-circuit cascade whose fourth arm is the credential's, so a
 * screen that still demanded one would answer `Password can NOT be empty...` here and the user type's
 * own refusal would be unreachable from a browser. Clearing the user type after a successful read
 * leaves the three arms before it satisfied, which is the only state in which the last arm can speak.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function reachesTheUserTypeRefusalWithTheFourControls(): Promise<void> {
  const user = userEvent.setup();
  await renderWithOneRowRead(user, ALICE);

  await user.clear(control('userType'));
  await user.click(keyButton(USER_UPDATE_KEY_LABELS.PFK05));
  await settle();

  expect(
    within(messageBand()).getByText(SHARED_MESSAGES.USER_TYPE_CAN_NOT_BE_EMPTY),
  ).toBeInTheDocument();
  expect(screen.queryByText(SHARED_MESSAGES.PASSWORD_CAN_NOT_BE_EMPTY)).toBeNull();
  expect(updateUserMock).not.toHaveBeenCalled();
}

/**
 * A committed save transmits exactly the three members the update contract declares.
 *
 * Assumptions: the body's KEY SET is asserted, not merely the three values, because the defect this
 * closes was a fourth value being collected and dropped. A test that only checked the three names were
 * present would pass against a body that also carried a credential, which is the one shape the sealed
 * contract refuses and the one an operator would most reasonably believe had been sent.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function transmitsExactlyTheThreeDeclaredMembers(): Promise<void> {
  const user = userEvent.setup();
  await renderWithOneRowRead(user, ALICE);
  getUserMock.mockResolvedValueOnce(ALICE);
  updateUserMock.mockResolvedValueOnce({ ...ALICE, firstName: 'ALICIA' });

  await user.clear(control('firstName'));
  await user.type(control('firstName'), 'ALICIA');
  await user.click(keyButton(USER_UPDATE_KEY_LABELS.PFK05));
  await settle();
  await settle();

  expect(updateUserMock).toHaveBeenCalledTimes(1);
  const [, body] = updateUserMock.mock.calls[0] as [string, UpdateUserRequest];
  expect(Object.keys(body).sort()).toStrictEqual(['firstName', 'lastName', 'userType']);
  expect(body).toStrictEqual({ firstName: 'ALICIA', lastName: ALICE.lastName, userType: 'U' });
}

/**
 * A read superseded by a read for a different user does not seed the form.
 *
 * Assumptions: the FIRST read is released LAST, which is the only ordering in which the defect shows.
 * Both responses are correct answers to the questions that were asked; the fault is that the later
 * arrival wins regardless of which question it answered, so releasing them in request order would leave
 * the right row on screen for the wrong reason.
 *
 * Assumptions: the route is changed by a link rather than by remounting at the second address, because a
 * remount discards the outstanding closure and there is then no race left to lose.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function discardsAReadSupersededByANewerRead(): Promise<void> {
  const first = deferred<UserResponse>();
  const second = deferred<UserResponse>();
  getUserMock.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
  const user = userEvent.setup();
  renderScreen(ALICE.userId, BOB.userId);

  await user.click(screen.getByRole('link', { name: SWITCH_USER_LABEL }));
  await act(
    /**
     * Releases the second read, then the first, so the superseded answer arrives last.
     * @returns {Promise<void>} Resolves once both continuations have run.
     */
    async (): Promise<void> => {
      second.release(BOB);
      await second.promise;
      first.release(ALICE);
      await first.promise;
    },
  );

  expect(control('firstName')).toHaveValue(BOB.firstName);
  expect(control('lastName')).toHaveValue(BOB.lastName);
  expect(control('userType')).toHaveValue(BOB.userType);
}

/**
 * A read superseded by a clear leaves the screen empty.
 *
 * Assumptions: the message band is asserted as well as the controls. The seeding and the
 * `Press PF5 key to save your updates ...` sentence are set by the same continuation, so a fix that
 * guarded the values and not the sentence would leave an emptied screen inviting a save of nothing --
 * and the sentence is the half an operator reads first.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function discardsAReadSupersededByAClear(): Promise<void> {
  const outstanding = deferred<UserResponse>();
  getUserMock.mockReturnValueOnce(outstanding.promise);
  const user = userEvent.setup();
  renderScreen(ALICE.userId, null);

  await user.click(keyButton(USER_UPDATE_KEY_LABELS.PFK04));
  await act(
    /**
     * Releases the abandoned read.
     * @returns {Promise<void>} Resolves once its continuation has run.
     */
    async (): Promise<void> => {
      outstanding.release(ALICE);
      await outstanding.promise;
    },
  );

  expect(control('userId')).toHaveValue('');
  expect(control('firstName')).toHaveValue('');
  expect(control('lastName')).toHaveValue('');
  expect(
    within(messageBand()).queryByText(UPDATE_MESSAGES.PRESS_PF5_KEY_TO_SAVE_YOUR_UPDATES),
  ).toBeNull();
}

/**
 * A write superseded by a clear claims no success.
 *
 * Assumptions: the composed success sentence is matched by its stable tail rather than in full, because
 * the reference composes it from three parts around the identifier and the tail is the part that cannot
 * be produced by anything else on this screen.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function discardsAWriteSupersededByAClear(): Promise<void> {
  const user = userEvent.setup();
  await renderWithOneRowRead(user, ALICE);
  getUserMock.mockResolvedValueOnce(ALICE);
  const write = deferred<UserResponse>();
  updateUserMock.mockReturnValueOnce(write.promise);

  await user.clear(control('firstName'));
  await user.type(control('firstName'), 'ALICIA');
  await user.click(keyButton(USER_UPDATE_KEY_LABELS.PFK05));
  await settle();
  await user.click(keyButton(USER_UPDATE_KEY_LABELS.PFK04));
  await act(
    /**
     * Releases the abandoned write.
     * @returns {Promise<void>} Resolves once its continuation has run.
     */
    async (): Promise<void> => {
      write.release({ ...ALICE, firstName: 'ALICIA' });
      await write.promise;
    },
  );

  expect(updateUserMock).toHaveBeenCalledTimes(1);
  expect(within(messageBand()).queryByText(/has been updated/)).toBeNull();
  expect(control('firstName')).toHaveValue('');
}

/**
 * The three keys that open a turn stand down while one is outstanding, and the two exits do not.
 *
 * Assumptions: the enabled halves are asserted beside the disabled ones. Disabling every key would
 * satisfy the first three assertions and would trap an operator on a slow request with no way to clear
 * the screen or leave it, which is the failure the two exemptions exist to avoid.
 *
 * Assumptions: no invalid-key sentence may appear either. A stood-down key reports through the same
 * channel an unbound key does, so a screen that did not distinguish the two reasons would answer
 * `Invalid key pressed...` about a key whose own legend is on the glass in front of the operator.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function standsTheTurnKeysDownWhileARequestIsOutstanding(): Promise<void> {
  const outstanding = deferred<UserResponse>();
  getUserMock.mockReturnValueOnce(outstanding.promise);
  const user = userEvent.setup();
  renderScreen(ALICE.userId, null);

  expect(keyButton(USER_UPDATE_KEY_LABELS.ENTER)).toBeDisabled();
  expect(keyButton(USER_UPDATE_KEY_LABELS.PFK03)).toBeDisabled();
  expect(keyButton(USER_UPDATE_KEY_LABELS.PFK05)).toBeDisabled();
  expect(keyButton(USER_UPDATE_KEY_LABELS.PFK04)).toBeEnabled();
  expect(keyButton(USER_UPDATE_KEY_LABELS.PFK12)).toBeEnabled();

  await user.keyboard('{F5}');
  expect(within(messageBand()).queryByText(INVALID_KEY_PRESSED)).toBeNull();

  await act(
    /**
     * Releases the outstanding response so the case leaves nothing in flight.
     * @returns {Promise<void>} Resolves once its continuation has run.
     */
    async (): Promise<void> => {
      outstanding.release(ALICE);
      await outstanding.promise;
    },
  );
}

/**
 * PF3 pressed during another turn neither writes nor leaves the screen.
 *
 * Assumptions: BOTH halves are asserted, and the second is the one that fails under the defect. PF3
 * sequenced its transfer of control on the promise the save returned, and a save refused for being
 * concurrent returned an ALREADY-RESOLVED promise -- so the transfer ran immediately and the operator
 * left the screen having saved nothing, on the one key whose legend promises `F3=Save&&Exit`.
 * @returns {Promise<void>} Resolves once the assertions have run.
 */
async function doesNotExitWithoutSavingWhileATurnIsOutstanding(): Promise<void> {
  const outstanding = deferred<UserResponse>();
  getUserMock.mockReturnValueOnce(outstanding.promise);
  const user = userEvent.setup();
  renderScreen(ALICE.userId, null);

  await user.keyboard('{F3}');
  await settle();

  expect(updateUserMock).not.toHaveBeenCalled();
  expect(screen.queryByText(ADMIN_SENTINEL_TEXT)).toBeNull();

  await act(
    /**
     * Releases the outstanding read so the case leaves nothing in flight.
     * @returns {Promise<void>} Resolves once its continuation has run.
     */
    async (): Promise<void> => {
      outstanding.release(ALICE);
      await outstanding.promise;
    },
  );
}

/**
 * Registers every case, and resets the two spies between them.
 * @returns {void} Nothing; the registrations are the effect.
 */
function userUpdateCases(): void {
  beforeEach(
    /**
     * Clears any response a previous case queued.
     * @returns {void} Nothing.
     */
    (): void => {
      getUserMock.mockReset();
      updateUserMock.mockReset();
    },
  );
  afterEach(
    /**
     * Clears any spy state a case installed.
     * @returns {void} Nothing.
     */
    (): void => {
      vi.restoreAllMocks();
    },
  );

  it('paints no credential control', paintsNoCredentialControl);
  it('names exactly the four admitted fields', namesExactlyTheFourAdmittedFields);
  it(
    'reaches the user-type refusal with the four controls',
    reachesTheUserTypeRefusalWithTheFourControls,
  );
  it('transmits exactly the three declared members', transmitsExactlyTheThreeDeclaredMembers);
  it('discards a read superseded by a newer read', discardsAReadSupersededByANewerRead);
  it('discards a read superseded by a clear', discardsAReadSupersededByAClear);
  it('discards a write superseded by a clear', discardsAWriteSupersededByAClear);
  it(
    'stands the turn keys down while a request is outstanding',
    standsTheTurnKeysDownWhileARequestIsOutstanding,
  );
  it(
    'does not exit without saving while a turn is outstanding',
    doesNotExitWithoutSavingWhileATurnIsOutstanding,
  );
}

describe('user update screen', userUpdateCases);
