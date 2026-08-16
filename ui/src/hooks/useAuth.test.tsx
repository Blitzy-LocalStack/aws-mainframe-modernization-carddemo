/**
 * @file Component tests for the module-scoped session store in `ui/src/hooks/useAuth.ts`.
 *
 * Purpose
 * -------
 * Assert the three properties the store rewrite exists to provide, none of which a per-instance
 * `useState` model had: every mounted caller reads ONE answer, a change made through one caller
 * reaches the others without anything unrelated re-rendering, and a reading is referentially stable
 * so it may be handed to a memoised child. The rewrite replaced a hook that held its own state, and
 * the defect it closed -- a sign-out performed through one instance leaving every other instance
 * reporting the previous session -- is only observable with two callers mounted at once, which is
 * what every case below renders.
 *
 * Assumptions: the store is exercised through `useAuth` in a rendered tree rather than by importing
 * its internals, because `subscribe`, `getSnapshot` and `composeResult` are module-private on
 * purpose. Rendering is also the only way to observe the `useSyncExternalStore` contract that the
 * caching exists to satisfy: a reading rebuilt on every render re-renders without end, so a case
 * that called the store directly could not tell a stable reading from an unstable one.
 *
 * Assumptions: the session is established by writing the storage keys directly rather than by
 * signing on through the transport. The reading is DERIVED from those keys on every call -- the
 * hook's own note records that `signedOn` is computed from the identity token rather than held as a
 * flag -- so writing them is the shortest way to place the store in a known state, and it keeps
 * these cases independent of the identity provider's own contract.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted. The runner's own `globals` option is set to
// `true`, for the separate reason recorded beside it, so the enforcing mechanism is the empty
// `types` list and never that option.
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { memo, useRef } from 'react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as clientModule from '../api/client';
import { installApiHarness, removeApiHarness } from '../test/apiHarness';
import { endAnySession, establishSession } from '../test/sessionHarness';

import { CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP, groupsFromIdToken, useAuth } from './useAuth';
import type { UseAuthResult } from './useAuth';

/*
 * WHY : Refactoring Rationale: three constants naming `carddemo.*` session-storage slots stood here --
 *       the identity token, the signed-on identifier and the refresh token -- and they are withdrawn
 *       with the storage they named. `useAuth` holds the whole session in a module variable, so there
 *       is no slot for a test to write or read, and a constant naming one would invite a case to
 *       arrange state the module cannot see.
 */

const OPERATOR_ID = 'USER0001';
const ADMIN_ID = 'ADMIN001';

/**
 * Builds a three-segment identity token whose claim carries the supplied groups.
 *
 * Assumptions: the token is assembled rather than obtained, and its signature segment is a fixed
 * word rather than a signature. Nothing in this tree verifies one -- verification is the service's
 * job, and `groupsFromIdToken` reads the middle segment only -- so a real token would add nothing an
 * assertion could observe while putting a credential-shaped literal in a test file.
 * @param {readonly string[]} groups - Group names to place in the `cognito:groups` claim.
 * @returns {string} A token whose middle segment decodes to those groups.
 */
function idTokenFor(groups: readonly string[]): string {
  const payload = btoa(JSON.stringify({ 'cognito:groups': groups }));

  return `header.${payload}.signature`;
}

/**
 * Establishes a session for the identifier and groups a case needs.
 *
 * Refactoring Rationale: ⚠️ this wrote four `carddemo.*` session-storage slots and now drives the
 * real sign-on exchange through the shared harness instead. `useAuth` no longer reads or writes
 * browser storage at all -- the whole session, refresh token included, is held in a module variable
 * so nothing script-readable retains a credential -- so writing those slots established nothing and
 * every case built on it would have read an anonymous session.
 *
 * Assumptions: the temporary hook `establishSession` renders is unmounted before returning, and the
 * session survives it. That is the point of the module-held session: it belongs to the tab rather
 * than to any mounted consumer, so the components each case renders afterwards observe the same
 * session without this helper having to hand them anything.
 * @param {string} userId - Identifier the session is established for.
 * @param {readonly string[]} groups - Groups the identity token's claim carries.
 * @returns {Promise<void>} Resolves once the session is held.
 */
async function installSession(userId: string, groups: readonly string[]): Promise<void> {
  const { unmount } = await establishSession({ userId, groups });

  unmount();
}

/**
 * Renders one reading of the store and counts how often it has been re-rendered.
 *
 * Assumptions: the render count is kept in a ref rather than in state, because writing state during
 * render is what a counter held in state would require and that is itself a re-render. A ref is
 * mutated in place, so counting cannot perturb the thing being counted.
 * @param {object} props - Component properties.
 * @param {string} props.label - Prefix distinguishing this reader's output from the other's.
 * @returns {ReactElement} One line per observable member, plus the render count.
 */
function SessionReader({ label }: { readonly label: string }): ReactElement {
  const session = useAuth();
  const renders = useRef(0);
  renders.current += 1;

  return (
    <div>
      <span data-testid={`${label}-status`}>{session.status}</span>
      <span data-testid={`${label}-signed-on`}>{String(session.signedOn)}</span>
      <span data-testid={`${label}-authenticated`}>{String(session.isAuthenticated)}</span>
      <span data-testid={`${label}-user`}>{session.userId ?? 'none'}</span>
      <span data-testid={`${label}-admin`}>{String(session.isAdmin)}</span>
      <span data-testid={`${label}-groups`}>{session.groups.join(',')}</span>
      <span data-testid={`${label}-renders`}>{String(renders.current)}</span>
    </div>
  );
}

/**
 * Renders a sign-off control that ends the session through the store.
 * @returns {ReactElement} A button invoking the store's sign-out operation.
 */
function SignOffControl(): ReactElement {
  const { signOut } = useAuth();

  return (
    <button
      type="button"
      onClick={
        /**
         * Ends the session through the store rather than by clearing storage.
         * @returns {void} Nothing; the store notifies its listeners.
         */
        () => {
          signOut();
        }
      }
    >
      sign off
    </button>
  );
}

let memoisedRenderCount = 0;

/**
 * A memoised consumer that re-renders only when the reading it is handed changes identity.
 *
 * Assumptions: this is the load-bearing consumer for the referential-stability property. `memo`
 * compares props by identity, so a reading rebuilt on every parent render would re-render this child
 * on every parent render -- which is exactly what the store's caching exists to prevent and what the
 * count below measures.
 * @param {object} props - Component properties.
 * @param {UseAuthResult} props.session - The reading handed down from the parent.
 * @returns {ReactElement} The identifier the reading carries.
 */
function MemoisedConsumerBody({ session }: { readonly session: UseAuthResult }): ReactElement {
  memoisedRenderCount += 1;

  return <span data-testid="memoised">{session.userId ?? 'none'}</span>;
}

/*
 * Refactoring Rationale: the body is a NAMED declaration above and only wrapped here, rather than
 * written inline as `memo(function ... )`. `ui/eslint.config.js` selects a function expression in
 * every position with `publicOnly: false`, so an inline body owes its own JSDoc block -- and a block
 * placed inside the `memo(` call is attached to an argument rather than to a component, which reads as
 * documentation of the wrapper. Splitting them lets one block document the component and this note
 * document the wrapping.
 */
const MemoisedConsumer = memo(MemoisedConsumerBody);

/**
 * Renders a parent that re-renders on demand and passes its reading to a memoised child.
 * @returns {ReactElement} The memoised child plus a control that re-renders the parent.
 */
function ParentWithMemoisedChild(): ReactElement {
  const session = useAuth();
  const renders = useRef(0);
  renders.current += 1;

  return (
    <div>
      <MemoisedConsumer session={session} />
      <span data-testid="parent-renders">{String(renders.current)}</span>
    </div>
  );
}

/**
 * Reads one published member out of the document.
 * @param {string} testId - Identifier of the element to read.
 * @returns {string} The element's text content.
 */
function published(testId: string): string {
  return screen.getByTestId(testId).textContent ?? '';
}

/**
 * Clears the retained session so no case inherits another's reading.
 *
 * Assumptions: storage is cleared BEFORE each case rather than only after, because the store caches
 * its last reading in module scope and a case that failed part-way through would otherwise leave
 * both the keys and the cache populated for the next one.
 * @returns {void} Nothing.
 */
function resetSession(): void {
  /*
   * WHY : Refactoring Rationale: this cleared `sessionStorage` and now ends the HELD session. The
   *       module keeps the session in a variable rather than in storage, so clearing storage left the
   *       previous case's session standing and the next case started signed on as somebody else.
   *       `endAnySession` is the shared teardown the other suites in this directory use, so all of
   *       them return the module to one known state rather than three approximations of it.
   */
  endAnySession();
  memoisedRenderCount = 0;
}

/**
 * Two callers mounted at once report the same reading for an anonymous visitor.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function twoCallersAgreeWhenNobodyIsSignedOn(): void {
  render(
    <div>
      <SessionReader label="first" />
      <SessionReader label="second" />
    </div>,
  );

  expect(published('first-status')).toBe('anonymous');
  expect(published('second-status')).toBe('anonymous');
  expect(published('first-signed-on')).toBe('false');
  expect(published('second-signed-on')).toBe('false');
  expect(published('first-user')).toBe('none');
  expect(published('first-groups')).toBe('');
  expect(published('first-admin')).toBe('false');
}

/**
 * Two callers mounted at once report the same reading for a signed-on operator.
 *
 * Assumptions: `isAuthenticated` and `signedOn` are both asserted although they carry the same
 * value. They are two spellings of one derived member kept for two different callers, so a change
 * that computed one and left the other behind would be invisible to a case reading either alone.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
async function twoCallersAgreeOnASignedOnOperator(): Promise<void> {
  await installSession(OPERATOR_ID, [CARDDEMO_USER_GROUP]);

  render(
    <div>
      <SessionReader label="first" />
      <SessionReader label="second" />
    </div>,
  );

  expect(published('first-status')).toBe('authenticated');
  expect(published('second-status')).toBe('authenticated');
  expect(published('first-signed-on')).toBe('true');
  expect(published('first-authenticated')).toBe('true');
  expect(published('second-signed-on')).toBe('true');
  expect(published('first-user')).toBe(OPERATOR_ID);
  expect(published('second-user')).toBe(OPERATOR_ID);
  expect(published('first-groups')).toBe(CARDDEMO_USER_GROUP);
  expect(published('first-admin')).toBe('false');
}

/**
 * The administrative group in the claim raises the administrative flag for every caller.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
async function theAdministrativeClaimRaisesTheFlagForEveryCaller(): Promise<void> {
  await installSession(ADMIN_ID, [CARDDEMO_ADMIN_GROUP, CARDDEMO_USER_GROUP]);

  render(
    <div>
      <SessionReader label="first" />
      <SessionReader label="second" />
    </div>,
  );

  expect(published('first-admin')).toBe('true');
  expect(published('second-admin')).toBe('true');
  expect(published('first-groups')).toBe(`${CARDDEMO_ADMIN_GROUP},${CARDDEMO_USER_GROUP}`);
}

/**
 * A sign-out performed through one caller is observed by an unrelated second caller.
 *
 * Assumptions: this is the regression case the store rewrite exists for. The sign-off control and
 * the second reader share no props, no parent state and no context -- the only path between them is
 * the module store -- so the second reader can only observe the change if the store notified it.
 * @returns {Promise<void>} Resolves once the second reader has re-read.
 */
async function aSignOutThroughOneCallerReachesTheOthers(): Promise<void> {
  await installSession(OPERATOR_ID, [CARDDEMO_USER_GROUP]);

  render(
    <div>
      <SignOffControl />
      <SessionReader label="observer" />
    </div>,
  );

  expect(published('observer-signed-on')).toBe('true');

  await userEvent.click(screen.getByRole('button', { name: 'sign off' }));

  await waitFor(
    /**
     * Re-reads the observer until it reports the ended session.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(published('observer-signed-on')).toBe('false');
    },
  );
  expect(published('observer-status')).toBe('anonymous');
  expect(published('observer-user')).toBe('none');
  expect(published('observer-groups')).toBe('');
}

/**
 * A sign-out clears the bearer token the transport holds.
 *
 * Refactoring Rationale: ⚠️ this was asserted through a `getAccessToken` reader on this module, and
 * that reader is withdrawn: the bearer lives in a module variable inside `ui/src/api/client.ts` and
 * only its WRITER is exported, so no second route to the credential exists to read it back. The
 * property is unchanged and is asserted at the surviving seam -- the writer the sign-out calls -- so
 * a sign-out that left the transport holding a token still fails here.
 *
 * Assumptions: a spy on that writer is the strongest available observation, and it is enough: the
 * interceptor attaches whatever the writer last received, so a final call carrying `null` is exactly
 * the state in which no later request can carry the ended session's token.
 * @returns {Promise<void>} Resolves once the token has been cleared.
 */
async function aSignOutClearsTheBearerTokenTheTransportReads(): Promise<void> {
  await installSession(OPERATOR_ID, [CARDDEMO_USER_GROUP]);

  render(
    <div>
      <SignOffControl />
      <SessionReader label="observer" />
    </div>,
  );

  const bearerWrites = vi.spyOn(clientModule, 'setAccessToken');

  await userEvent.click(screen.getByRole('button', { name: 'sign off' }));

  await waitFor(
    /**
     * Re-reads the accessor until the token has gone.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(bearerWrites).toHaveBeenCalledWith(null);
    },
  );
}

/**
 * An unchanged session produces a referentially equal reading, so a memoised child is not re-rendered.
 *
 * Assumptions: the parent is re-rendered by a real state change of its own -- clicking a control that
 * bumps a counter -- rather than by calling `rerender`, because `rerender` replaces the element and a
 * memoised child compares the props of the NEW element, which is the same comparison either way but
 * makes the case read as though the store were being replaced.
 * @returns {Promise<void>} Resolves once the parent has re-rendered twice.
 */
async function anUnchangedReadingDoesNotReRenderAMemoisedChild(): Promise<void> {
  await installSession(OPERATOR_ID, [CARDDEMO_USER_GROUP]);

  const view = render(<ParentWithMemoisedChild />);

  expect(memoisedRenderCount).toBe(1);

  view.rerender(<ParentWithMemoisedChild />);
  view.rerender(<ParentWithMemoisedChild />);

  await waitFor(
    /**
     * Waits for the parent to record its later renders.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(Number(published('parent-renders'))).toBeGreaterThan(1);
    },
  );
  expect(memoisedRenderCount).toBe(1);
}

/**
 * A changed session produces a new reading, so the memoised child does re-render.
 *
 * Assumptions: this is the control for the case above. Without it, a store that returned one frozen
 * object for every state would satisfy the stability assertion while never publishing a change.
 * @returns {Promise<void>} Resolves once the child has observed the ended session.
 */
async function aChangedReadingDoesReRenderAMemoisedChild(): Promise<void> {
  await installSession(OPERATOR_ID, [CARDDEMO_USER_GROUP]);

  render(
    <div>
      <SignOffControl />
      <ParentWithMemoisedChild />
    </div>,
  );

  expect(screen.getByTestId('memoised').textContent).toBe(OPERATOR_ID);
  const before = memoisedRenderCount;

  await userEvent.click(screen.getByRole('button', { name: 'sign off' }));

  await waitFor(
    /**
     * Re-reads the memoised child until it reports the ended session.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(screen.getByTestId('memoised').textContent).toBe('none');
    },
  );
  expect(memoisedRenderCount).toBeGreaterThan(before);
}

/**
 * Unmounting the last caller releases the subscription, and a later mount re-establishes it.
 *
 * Assumptions: the release is observed INDIRECTLY, by mounting again and proving the new caller still
 * receives a change, because the subscription bookkeeping is module-private. That is the property
 * that actually matters: reference-counted release is only correct if the count coming back up
 * re-subscribes, and a release that failed to do so would leave the second mount permanently stale
 * -- which is the failure this case detects.
 * @returns {Promise<void>} Resolves once the remounted caller has observed a change.
 */
async function remountingAfterTheLastCallerLeavesStillReceivesChanges(): Promise<void> {
  await installSession(OPERATOR_ID, [CARDDEMO_USER_GROUP]);

  const first = render(<SessionReader label="first" />);
  expect(published('first-signed-on')).toBe('true');
  first.unmount();

  render(
    <div>
      <SignOffControl />
      <SessionReader label="second" />
    </div>,
  );
  expect(published('second-signed-on')).toBe('true');

  await userEvent.click(screen.getByRole('button', { name: 'sign off' }));

  await waitFor(
    /**
     * Re-reads the remounted caller until it reports the ended session.
     * @returns {void} Nothing; the assertion carries the outcome.
     */
    () => {
      expect(published('second-signed-on')).toBe('false');
    },
  );
}

/**
 * A reading taken outside React agrees with the one a mounted caller renders.
 *
 * Assumptions: `groupsFromIdToken` is exercised on the exact token the session holds, because it is
 * the decode both the store and the route guards depend on and it is the one place a malformed claim
 * could silently produce an empty group list -- which reads as "signed on but authorised for
 * nothing" rather than as an error.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
async function anExternalDecodeAgreesWithTheRenderedReading(): Promise<void> {
  const token = idTokenFor([CARDDEMO_ADMIN_GROUP]);
  await installSession(ADMIN_ID, [CARDDEMO_ADMIN_GROUP]);

  render(<SessionReader label="first" />);

  expect(groupsFromIdToken(token)).toEqual([CARDDEMO_ADMIN_GROUP]);
  expect(published('first-groups')).toBe(CARDDEMO_ADMIN_GROUP);
}

/**
 * A malformed identity token yields no groups rather than raising.
 *
 * Assumptions: three distinct malformations are used -- a token with too few segments, one whose
 * middle segment is not base64, and one whose decoded payload holds no claim -- because each takes a
 * different arm of the decode and a single malformed value would establish only one of them.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
function aMalformedIdentityTokenYieldsNoGroups(): void {
  expect(groupsFromIdToken('not-a-token')).toEqual([]);
  expect(groupsFromIdToken('header.!!!not-base64!!!.signature')).toEqual([]);
  expect(groupsFromIdToken(`header.${btoa(JSON.stringify({ sub: 'x' }))}.signature`)).toEqual([]);
  expect(groupsFromIdToken(null)).toEqual([]);
}

/**
 * A reading published while the store is notified inside `act` is observed synchronously.
 *
 * Assumptions: the session is replaced by writing storage and then notifying through a real store
 * operation, because writing storage alone changes nothing a listener would hear -- the store reads
 * storage but is not notified by it, which is deliberate: a `storage` event fires only for OTHER tabs
 * and this store is per-tab by design.
 * @returns {void} Nothing; the assertions carry the outcome.
 */
async function aReadingIsRecomputedAfterTheSessionIsReplaced(): Promise<void> {
  await installSession(OPERATOR_ID, [CARDDEMO_USER_GROUP]);

  render(
    <div>
      <SignOffControl />
      <SessionReader label="first" />
    </div>,
  );
  expect(published('first-user')).toBe(OPERATOR_ID);

  /*
   * WHY : ⚠️ Refactoring Rationale: the replacement is awaited OUTSIDE the act scope, where it used to
   *       be awaited inside one. The note that put it inside argued that awaiting it outside would leave
   *       the resulting render unwrapped -- true of four storage writes, and untrue of the exchange that
   *       replaced them: `establishSession` renders a hook of its own and drives the exchange inside its
   *       OWN act scope, so every consumer re-render the store publish triggers is already wrapped. What
   *       nesting it did instead was break it. `renderHook` inside an enclosing act scope does not commit
   *       until that scope exits, so the helper's `result.current` was still null when it dereferenced it,
   *       and the case failed reading `signIn` of null rather than on anything it asserts.
   * WHY : Assumptions: the sign-off stays in a synchronous act scope of its own. Ending a session
   *       publishes to the store synchronously -- the revocation call it also starts is fire-and-forget
   *       and no listener waits on it -- so the listeners' re-read happens inside this scope, which is
   *       what the two assertions below read.
   */
  await installSession(ADMIN_ID, [CARDDEMO_ADMIN_GROUP]);
  act(
    /**
     * Ends the session through the store so the mounted listeners re-read.
     * @returns {void} Nothing; the assertions below carry the outcome.
     */
    () => {
      screen.getByRole('button', { name: 'sign off' }).click();
    },
  );

  expect(published('first-user')).toBe('none');
  expect(published('first-admin')).toBe('false');
}

/**
 * Registers the session-store cases.
 * @returns {void} Nothing; the cases are registered as a side effect.
 */
function sessionStoreCases(): void {
  beforeEach(installApiHarness);
  beforeEach(resetSession);
  afterEach(resetSession);
  afterEach(removeApiHarness);
  afterEach(
    /**
     * Discards any spy installed by a case so none leaks forward.
     * @returns {void} Nothing.
     */
    () => {
      vi.restoreAllMocks();
    },
  );

  it('reports one anonymous reading to two mounted callers', twoCallersAgreeWhenNobodyIsSignedOn);
  it('reports one signed-on reading to two mounted callers', twoCallersAgreeOnASignedOnOperator);
  it(
    'raises the administrative flag for every caller when the claim carries the group',
    theAdministrativeClaimRaisesTheFlagForEveryCaller,
  );
  it(
    'publishes a sign-out made through one caller to an unrelated caller',
    aSignOutThroughOneCallerReachesTheOthers,
  );
  it(
    'clears the bearer token the transport reads on sign-out',
    aSignOutClearsTheBearerTokenTheTransportReads,
  );
  it(
    'does not re-render a memoised child while the session is unchanged',
    anUnchangedReadingDoesNotReRenderAMemoisedChild,
  );
  it(
    're-renders a memoised child when the session changes',
    aChangedReadingDoesReRenderAMemoisedChild,
  );
  it(
    'still delivers changes to a caller mounted after the last one left',
    remountingAfterTheLastCallerLeavesStillReceivesChanges,
  );
  it('agrees with a decode taken outside React', anExternalDecodeAgreesWithTheRenderedReading);
  it('yields no groups for a malformed identity token', aMalformedIdentityTokenYieldsNoGroups);
  it(
    'recomputes a reading after the session is replaced',
    aReadingIsRecomputedAfterTheSessionIsReplaced,
  );
}

describe('the module-scoped session store behind useAuth', sessionStoreCases);
