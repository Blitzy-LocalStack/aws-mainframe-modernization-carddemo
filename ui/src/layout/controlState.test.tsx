/**
 * @file Proves the shared busy and unavailable state helpers report what the screen rendered.
 *
 * Purpose
 * -------
 * A browser has two control states the reference terminal did not: a request in flight, and a control
 * that is present but cannot be used. A rendering review measured both being reported badly. `aria-busy`
 * reached 6 of 13 busy states and never a button, so the control an operator had just activated was the
 * one element that never said it was working, and no live region anywhere announced that a request had
 * started or finished. `aria-disabled` was absent on 6 of 6 unavailable controls, `pointer-events`
 * stayed `auto` on all six, and nothing said why any of them was unavailable.
 *
 * A third measurement is the one these cases pin hardest. The design system's busy wrapper puts
 * `aria-live="polite"` on its own root, so wrapping a form in it makes the form a live region — 1173×210
 * and 193 characters on the screen the review measured, re-announced in full on every re-render. The
 * wrapper spreads a caller's props after its own defaults, and `WRAPPED_BUSY_REGION_PROPS` is what uses
 * that seam. The case below asserts the override against the real component rather than against the
 * constant, because the constant is only correct for as long as that ordering holds.
 */

// Assumptions: every test API is imported rather than taken from an ambient global, because
// ui/tsconfig.json keeps its `types` list EMPTY -- so nothing is declared ambiently and an omitted
// import fails to compile on the symbol it omitted.
import { render, screen } from '@testing-library/react';
import { Button, Spin, Typography } from 'antd';
import { describe, expect, it } from 'vitest';

import {
  BUSY_ANNOUNCEMENT_TEST_ID,
  UNAVAILABLE_CONTROL_STYLE,
  WRAPPED_BUSY_REGION_PROPS,
  busyAnnouncement,
  busyProps,
  fieldHintId,
  unavailableProps,
} from './fieldHelp';

/** Stands for any sentence a screen announces; the helpers hold no user-visible string. */
const WORKING_SENTENCE = 'Starting the report run.';

/** Stands for the sentence that replaces it once the turn settles. */
const SETTLED_SENTENCE = 'The report run has started.';

/** Stands for any control identifier a screen derives its described-element identifiers from. */
const CONTROL_ID = 'report-submit';

/**
 * A button reports that it is working, which is the case the review found reported nowhere.
 *
 * Purpose: `aria-busy` was measured on no button in the application, and a button is where it matters
 * most — it is the element the operator acted on. The member has to be spreadable onto a design-system
 * control and reach the rendered element, which is why this renders one rather than inspecting the
 * returned object alone.
 * @returns {void} Nothing; assertions raise on failure.
 */
function reportsABusyButton(): void {
  render(
    <Button {...busyProps(true)} loading>
      {WORKING_SENTENCE}
    </Button>,
  );

  expect(screen.getByRole('button').getAttribute('aria-busy')).toBe('true');
}

/**
 * An idle control carries no busy attribute at all, rather than one reading false.
 *
 * Purpose: `aria-busy` defaults to false in the attribute's absence, so emitting `false` would add an
 * attribute mutation to every settle without adding a fact — and a mutation inside a live region is not
 * free. The absent form is also what lets a caller spread the result unconditionally.
 * @returns {void} Nothing; assertions raise on failure.
 */
function leavesAnIdleControlUnmarked(): void {
  expect(busyProps(false)).toEqual({});

  render(<Button {...busyProps(false)}>{WORKING_SENTENCE}</Button>);

  expect(screen.getByRole('button').hasAttribute('aria-busy')).toBe(false);
}

/**
 * The announcement region is present while idle, so its first transition is announced.
 *
 * Purpose: this is the property that makes the region work at all. A live region has to be in the
 * accessibility tree before its content changes; a region mounted with its text already in place is
 * frequently treated as initial content and read by nothing. Returning nothing while idle would
 * therefore lose the first announcement, which is the one telling the operator their key press was
 * received.
 * @returns {void} Nothing; assertions raise on failure.
 */
function keepsTheAnnouncementRegionMountedWhileIdle(): void {
  const { rerender } = render(busyAnnouncement(undefined));

  const region = screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID);

  expect(region.getAttribute('role'), 'role=status carries a polite live region').toBe('status');
  expect(region.textContent, 'nothing to announce yet').toBe('');

  rerender(busyAnnouncement(WORKING_SENTENCE));

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID).textContent).toBe(WORKING_SENTENCE);
  expect(
    screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID),
    'the same element must carry both, so the change is a change',
  ).toBe(region);

  rerender(busyAnnouncement(SETTLED_SENTENCE));

  expect(screen.getByTestId(BUSY_ANNOUNCEMENT_TEST_ID).textContent).toBe(SETTLED_SENTENCE);
}

/**
 * The announcement is scoped to the sentence and carried out of view.
 *
 * Purpose: the defect being replaced is a live region the size of a form. This asserts the replacement
 * announces one sentence and nothing else, and that it adds no second visible busy vocabulary beside
 * the spinner or the loading control the screen already renders.
 * @returns {void} Nothing; assertions raise on failure.
 */
function scopesTheAnnouncementToItsOwnSentence(): void {
  render(
    <div>
      {busyAnnouncement(WORKING_SENTENCE)}
      <Typography.Text>{SETTLED_SENTENCE}</Typography.Text>
    </div>,
  );

  const region = screen.getByRole('status');

  expect(region.textContent, 'the region holds the sentence alone').toBe(WORKING_SENTENCE);
  expect(region.style.position, 'and is out of view, not a second visible vocabulary').toBe(
    'absolute',
  );
  expect(region.style.clipPath).toBe('inset(50%)');
}

/**
 * The busy wrapper stops turning its whole subtree into a live region once the props are passed.
 *
 * Purpose: assert the override against the real component. The constant depends on the wrapper
 * spreading a caller's props after its own `aria-live`, which is an implementation ordering rather than
 * a documented API — so the fact worth pinning is the rendered outcome, which would change under the
 * wrapper if that ordering ever did.
 *
 * Assumptions: `aria-busy` is asserted to SURVIVE the override. A review found too few busy statements
 * rather than too many, and a region whose content is being replaced is exactly what the member is for;
 * it is only the live-region part that turns a large subtree into an announcement.
 * @returns {void} Nothing; assertions raise on failure.
 */
function stopsTheBusyWrapperFromAnnouncingItsWholeSubtree(): void {
  const { container } = render(
    <div>
      <Spin data-testid="unsuppressed" spinning>
        <Typography.Text>{SETTLED_SENTENCE}</Typography.Text>
      </Spin>
      <Spin {...WRAPPED_BUSY_REGION_PROPS} data-testid="suppressed" spinning>
        <Typography.Text>{SETTLED_SENTENCE}</Typography.Text>
      </Spin>
    </div>,
  );

  const unsuppressed = screen.getByTestId('unsuppressed');
  const suppressed = screen.getByTestId('suppressed');

  expect(
    unsuppressed.getAttribute('aria-live'),
    'the measured defect: the wrapper makes its subtree a live region',
  ).toBe('polite');
  expect(suppressed.getAttribute('aria-live')).toBe('off');
  expect(suppressed.getAttribute('aria-busy'), 'busyness is kept, only the announcing stops').toBe(
    'true',
  );
  expect(
    container.querySelectorAll('[aria-live="polite"]'),
    'exactly one wrapper is left announcing, and it is the unsuppressed one',
  ).toHaveLength(1);
}

/**
 * Groups the busy-state cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function busyStateCases(): void {
  it('reports a busy button', reportsABusyButton);
  it('leaves an idle control unmarked', leavesAnIdleControlUnmarked);
  it(
    'keeps the announcement region mounted while idle',
    keepsTheAnnouncementRegionMountedWhileIdle,
  );
  it('scopes the announcement to its own sentence', scopesTheAnnouncementToItsOwnSentence);
  it(
    'stops the busy wrapper from announcing its whole subtree',
    stopsTheBusyWrapperFromAnnouncingItsWholeSubtree,
  );
}

describe('busy state', busyStateCases);

/**
 * An unavailable control says that it is unavailable and points at the reason.
 *
 * Purpose: both halves of the finding at once. `aria-disabled` was absent on every unavailable control
 * measured, and nothing explained why any of them was unavailable — and stating the first without the
 * second leaves an operator with a dead control and no remedy.
 * @returns {void} Nothing; assertions raise on failure.
 */
function statesUnavailabilityAndItsReason(): void {
  const reasonId = fieldHintId(CONTROL_ID);

  render(
    <div>
      <Button
        {...unavailableProps(true, reasonId)}
        id={CONTROL_ID}
        style={UNAVAILABLE_CONTROL_STYLE}
      >
        {WORKING_SENTENCE}
      </Button>
      <span id={reasonId}>{SETTLED_SENTENCE}</span>
    </div>,
  );

  const control = screen.getByRole('button');

  expect(control.getAttribute('aria-disabled')).toBe('true');
  expect(control.getAttribute('aria-describedby')).toBe(reasonId);
  expect(
    document.getElementById(reasonId)?.textContent,
    'the reference must be to a RENDERED element, never dangling',
  ).toBe(SETTLED_SENTENCE);
}

/**
 * An unavailable control on this path is inert to the pointer as well as to assistive technology.
 *
 * Purpose: `pointer-events` was measured as `auto` on all six unavailable controls. On the
 * `aria-disabled` path that is a real contradiction rather than a cosmetic one — the element advertises
 * itself as unavailable and still answers a click — because nothing else is stopping the activation.
 * @returns {void} Nothing; assertions raise on failure.
 */
function makesAnUnavailableControlInert(): void {
  render(
    <Button {...unavailableProps(true, undefined)} style={UNAVAILABLE_CONTROL_STYLE}>
      {WORKING_SENTENCE}
    </Button>,
  );

  expect(screen.getByRole('button').style.pointerEvents).toBe('none');
}

/**
 * An available control carries neither member, and a reason may be pointed at without one.
 *
 * Purpose: the empty result is what lets a caller spread this unconditionally, and the two members are
 * genuinely independent — a control can be available and still described by a sentence explaining what
 * it will do, which is why the helper does not gate the reference on the state.
 * @returns {void} Nothing; assertions raise on failure.
 */
function keepsTheTwoMembersIndependent(): void {
  expect(unavailableProps(false, undefined)).toEqual({});
  expect(unavailableProps(false, fieldHintId(CONTROL_ID))).toEqual({
    'aria-describedby': fieldHintId(CONTROL_ID),
  });
  expect(unavailableProps(true, undefined)).toEqual({ 'aria-disabled': true });
}

/**
 * Groups the unavailable-state cases.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function unavailableStateCases(): void {
  it('states unavailability and its reason', statesUnavailabilityAndItsReason);
  it('makes an unavailable control inert', makesAnUnavailableControlInert);
  it('keeps the two members independent', keepsTheTwoMembersIndependent);
}

describe('unavailable state', unavailableStateCases);
