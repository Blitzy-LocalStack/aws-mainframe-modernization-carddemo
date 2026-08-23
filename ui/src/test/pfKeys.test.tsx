/**
 * @file Pins the CardDemo PF-key contract: the attention-identifier (AID) normalisation in
 * `ui/src/layout/usePfKeys.ts` and the visible legend in `ui/src/layout/PfKeyBar.tsx`.
 *
 * Purpose
 * -------
 * Seven contracts are pinned here, each one derived from a line of the reference-only baseline
 * rather than from anybody's reading of it:
 *
 * 1. The AID domain is the sixteen `88`-level values of `CCARD-AID PIC X(5)` at
 *    `app/cpy/CVCRD01Y.cpy:L3-L19`.
 * 2. `Enter` normalises to `ENTER`, per `app/cpy/CSSTRPFY.cpy:L22-L23`.
 * 3. `F1` through `F12` normalise to `PFK01` through `PFK12`, per `app/cpy/CSSTRPFY.cpy:L30-L53`.
 * 4. `F13` through `F24` are ALIASES of `F1` through `F12` and dispatch identically, per the twelve
 *    `WHEN` clauses at `app/cpy/CSSTRPFY.cpy:L54-L77`. This is the headline contract.
 * 5. `CLEAR`, `PA1` and `PA2` are normalised by that copybook and acted on by no program at all, so
 *    nothing is bound to them.
 * 6. An unmapped attention key raises `CCDA-MSG-INVALID-KEY`, declared `PIC X(50)` at
 *    `app/cpy/CSMSG01Y.cpy:L20-L21`, on the screens that emit it and on no others.
 * 7. The legend is a PER-SCREEN descriptor array, because the seventeen measured row-24 legends
 *    differ from one another; and the paging keys take their availability from the keyset envelope,
 *    which `app/cbl/COCRDLIC.cbl:L229-L244` already holds as a cursor rather than as a page number.
 *
 * Parameters, Returns, Exceptions (the module analogues)
 * -----------------------------------------------------
 * This module takes no parameters: `ui/vitest.config.ts` collects it by its `src/**` glob and it
 * reads no environment value, no argument and no file. It returns no value; its result is the
 * runner's verdict. It raises nothing of its own — every throw a case can produce comes either from
 * an assertion or from `pressPfKey` in `ui/src/test/setup.ts`, which is documented to reject for an
 * AID that has no browser key and is used deliberately for exactly that outcome below.
 *
 * Why this file exists rather than the same assertions in each screen file
 * ----------------------------------------------------------------------
 * `usePfKeys.ts` names `ui/src/test/**` as the consumer that drives it through
 * `@testing-library/user-event`, and `PfKeyBar.tsx` names the same consumer for its labels and its
 * click-versus-keypress equivalence. The alias folding, the unbound keys and the invalid-key message
 * are ONE hook's behaviour, identical on every screen, so asserting them in each of the 21 screen
 * files would be twenty-one copies of one fact — which is the restatement Rule 1's Forbidden
 * Patterns rule out. What stays per-screen is each screen's own key SET and its own legend text; the
 * mechanism lives here.
 *
 * ⚠️ Why this matters more than a component test usually does
 * ----------------------------------------------------------
 * These cases are the ONLY verification the PF-key contract gets anywhere in the repository.
 * `tests/README.md` §1.1 states it plainly: "Online `CO*` CICS programs cannot run end-to-end
 * without a CICS runtime (absent on the runner); only their extractable field-validation logic is
 * unit-tested." The existing three-layer COBOL suite is the parity oracle for the BATCH programs; it
 * holds no golden master and no unit test for any keyboard interaction, and it cannot, because a
 * 3270 attention identifier needs a terminal and a transaction manager to raise. So there is nothing
 * behind this file to catch what it misses.
 *
 * Documentation obligation
 * ------------------------
 * Rule 1 (Explainability) requires a docstring on every function and module entry point stating
 * purpose, parameters, return value and exceptions, and an inline comment justifying every
 * non-obvious decision under one of four named categories. `tests/README.md` §12 imposes the
 * identical obligation on "every new test, fixture builder, helper, mock, and runner routine" and
 * calls it "a hard review gate". The two agree, so this file extends an established house convention
 * rather than importing a foreign one, and it is written to
 * `docs/CODE_DOCUMENTATION_STANDARD.md`.
 *
 * Assumptions: the four rationale labels are written in the PLURAL canonical form —
 * `Assumptions:`, `Trade-offs:`, `Alternatives Considered:`, `Refactoring Rationale:`. That
 * standard's own section on the labels mandates that spelling character-for-character, states that
 * "the forms are never mixed inside one file", and records that the singular idiom belongs to the
 * reference-only COBOL suite. `config/rule1/rule1_gate.py` enforces the distinction mechanically:
 * its `CANONICAL_LABELS` holds the four plural forms and its `singular` pattern reports
 * `Assumption:` and `Trade-off:` as violations. The same gate reports any `WHAT:` token in a comment
 * outside a file's leading header block, so statement-level rationale below is introduced by
 * `WHY :` alone.
 *
 * Assumptions: the runner's own names are IMPORTED from `vitest` rather than taken as ambient
 * globals, even though `ui/vitest.config.ts` sets `globals: true`. `ui/tsconfig.json` keeps its
 * `types` list empty and its own comment records that the empty list "is what obliges a test to
 * import `describe`, `it` and `expect` from 'vitest' by name -- with nothing declared ambiently, an
 * omitted import fails to compile on the symbol it omitted". The runner option governs run time and
 * the compiler option governs `tsc --noEmit`, which is a required step in
 * `.github/workflows/ui-ci.yml`; only the import satisfies both. Every sibling test file in this
 * package does the same.
 *
 * Refactoring Rationale: every callback is a named function declaration rather than an inline arrow,
 * for the two reasons `ui/src/layout/appShell.test.tsx` records — `jsdoc/require-jsdoc` runs with
 * `publicOnly: false` and a `* > ArrowFunctionExpression` context, so a documentation block is owed
 * on a function expression in any position, and Prettier detaches a block comment that follows an
 * argument comma.
 */

import { screen, within } from '@testing-library/react';
import { useState } from 'react';
import type { ReactElement } from 'react';
import { describe, expect, it } from 'vitest';

import type { PageResponse } from '../api/types';
import { usePagedQuery } from '../hooks/usePagedQuery';
import type { PagedQueryRequest } from '../hooks/usePagedQuery';
import { MessageBand } from '../layout/MessageBand';
import {
  PF_KEY_BAR_REGION_LABEL,
  PRIMARY_ACTION_AIDS,
  PfKeyBar,
  UNIFORM_PF_KEY_LABELS,
  decodeBmsLegendText,
} from '../layout/PfKeyBar';
import {
  CICS_AIDS,
  DEFAULT_PF_KEY_ACTIONS,
  KEYBOARD_KEY_TO_AID,
  PF_KEY_ALIASES,
  resolveAid,
  usePfKeys,
} from '../layout/usePfKeys';
import type {
  CicsAid,
  PfKeyAction,
  PfKeyBinding,
  PfKeyHandlerEntry,
  PfKeyHandlerMap,
  PfKeyRejection,
} from '../layout/usePfKeys';
import {
  ACCOUNT_VIEW_KEY_LABELS,
  COMMON_MESSAGES,
  INVALID_KEY_PRESSED,
  MAIN_MENU_KEY_LABELS,
  STATUS_MESSAGES,
  TRANSACTION_DETAIL_KEY_LABELS,
  USER_ADD_KEY_LABELS,
  USER_DELETE_KEY_LABELS,
} from '../messages/messages';
import { expectVerbatimMessage, pageResponse, pressPfKey, renderWithProviders } from './setup';
import type { HarnessRenderResult } from './setup';

/*
 * WHY : Assumptions: nothing below re-declares the AID union, the alias table or the browser-key
 *       lookup, and that restriction is the reason the file can catch anything at all. A private
 *       copy of a mapping in the test that verifies that mapping agrees with itself by
 *       construction, so a genuine regression in `usePfKeys.ts` would pass. The tables are
 *       therefore imported and the copybook's ARITHMETIC is asserted against them instead: for each
 *       n the base key `F<n>` must normalise to `PFK<n>` and the alias key `F<n+12>` must normalise
 *       to the same AID. Both relations are computed here from n, so a single mis-transcribed
 *       `WHEN` clause in either range fails a case.
 */

/**
 * How many function keys the copybook normalises directly, before the aliases begin.
 *
 * Assumptions: twelve, counted from the `WHEN EIBAID IS EQUAL TO DFHPF1` through `DFHPF12` clauses
 * at `app/cpy/CSSTRPFY.cpy:L30-L53`, and independently confirmed by the twelve `PFK01` through
 * `PFK12` condition names at `app/cpy/CVCRD01Y.cpy:L8-L19`.
 */
const DIRECT_PF_KEY_COUNT = 12;

/**
 * The offset the copybook folds the second bank of function keys back by.
 *
 * Assumptions: twelve, because `app/cpy/CSSTRPFY.cpy:L54-L77` sets `CCARD-AID-PFK01` for `DFHPF13`
 * and `CCARD-AID-PFK12` for `DFHPF24` — so alias number n names the same AID as base number n − 12.
 * The value equals {@link DIRECT_PF_KEY_COUNT} and is still declared separately, because the two are
 * different facts that happen to share a number: one is how many keys exist, the other is how far
 * the alias bank is displaced. Collapsing them would hide which of the two a reader is looking at.
 */
const PF_KEY_ALIAS_OFFSET = 12;

/**
 * The seven attention identifiers the shared shell gives a fixed meaning, with the meaning.
 *
 * Assumptions: this is the expected VALUE of {@link DEFAULT_PF_KEY_ACTIONS} rather than a second
 * copy of it — a case below asserts the two are equal, which is what makes writing it out useful
 * instead of circular. The seven pairings are the measured semantics of the baseline: ENTER submits,
 * PF3 goes back, PF4 clears, PF5 saves, PF7 pages backward, PF8 pages forward and PF12 cancels or
 * signs off. `app/cbl/COUSR02C.cbl:L110-L127` exhibits four of them in one `EVALUATE` — PF3 updates
 * and returns, PF4 clears the screen, PF5 updates in place and PF12 returns to the administrative
 * menu.
 */
const EXPECTED_DEFAULT_ACTIONS: Readonly<Partial<Record<CicsAid, PfKeyAction>>> = {
  ENTER: 'submit',
  PFK03: 'back',
  PFK04: 'clear',
  PFK05: 'save',
  PFK07: 'page-backward',
  PFK08: 'page-forward',
  PFK12: 'cancel',
};

/**
 * The three members of the AID domain that no program in the baseline acts on.
 *
 * ⚠️ Assumptions: this is an exhaustive search result, not an impression, and it is written down
 * because the instinct on meeting a normalised key that does nothing is to wire it up.
 * `app/cpy/CSSTRPFY.cpy:L24-L29` maps `DFHCLEAR`, `DFHPA1` and `DFHPA2` onto named flags, and
 * `app/cpy/CVCRD01Y.cpy:L5-L7` declares those flags as `'CLEAR'`, `'PA1  '` and `'PA2  '` — so a
 * reader reasonably expects three working keys. Searched across every program in `app/cbl` and in
 * all three extension trees (`app/app-authorization-ims-db2-mq`, `app/app-vsam-mq`,
 * `app/app-transaction-type-db2`), the six tokens `DFHCLEAR`, `DFHPA1`, `DFHPA2`,
 * `CCARD-AID-CLEAR`, `CCARD-AID-PA1` and `CCARD-AID-PA2` occur in ZERO programs; the only files
 * that hold any of them are the two copybooks that define them. The copybook normalises them and
 * nothing consumes the result.
 *
 * Assumptions: the trailing spaces of `'PA1  '` and `'PA2  '` are absent here because they only pad
 * the `PIC X(5)` field, which is the same reading `usePfKeys.ts` records for its own domain.
 */
const AIDS_NO_PROGRAM_ACTS_ON: readonly CicsAid[] = ['CLEAR', 'PA1', 'PA2'];

/**
 * Browser key names pressed to show that even an exactly-spelled attention key raises nothing.
 *
 * Assumptions: `'Clear'` is a real `KeyboardEvent.key` value in the UI Events key-values
 * specification, so it is the strongest available probe — a browser genuinely produces it. `'PA1'`
 * and `'PA2'` have no web spelling at all, and they are pressed under their terminal names
 * deliberately: if a future edit ever invented a key for them, that invention would almost certainly
 * use these names, and this is where it would fail.
 *
 * Trade-offs: driving these through the real keyboard path costs three key presses and proves
 * something the lookup assertion cannot. The lookup shows the table has no entry; the key press
 * shows the LISTENER treats an absent entry as silence rather than as an invalid key, which is a
 * separate branch of `usePfKeys` and the one a user would notice.
 */
const UNBOUND_BROWSER_KEY_NAMES: readonly string[] = ['Clear', 'PA1', 'PA2'];

/**
 * An ordered record of every AID a dispatch actually reached.
 */
interface KeyLog {
  /** The AIDs dispatched so far, in dispatch order. */
  readonly received: readonly CicsAid[];
  /**
   * Records one dispatched AID.
   * @param {CicsAid} aid - The canonical AID the handler was invoked with.
   * @returns {void} Nothing; the recording is the observable effect.
   */
  readonly record: (aid: CicsAid) => void;
}

/**
 * Creates one empty dispatch log.
 *
 * Alternatives Considered: a module-level array reset by `beforeEach`, and `vi.fn()`. The array is
 * rejected because two cases in the same file would share it, and a case that forgot the reset would
 * inherit the previous case's dispatches and pass for the wrong reason. `vi.fn()` is rejected for a
 * narrower reason: what these cases assert is not "was it called" but "which AID arrived, and in
 * what order, at WHICH of several handlers" — the alias contract is precisely a claim about two key
 * presses reaching the same one. A named log reads as that claim; a call-argument matrix does not.
 * `ui/vitest.config.ts` sets `clearMocks` and `restoreMocks`, so no reset would be owed either way,
 * and none is written here.
 * @returns {KeyLog} A fresh log whose `received` member is the live array `record` appends to.
 */
function createKeyLog(): KeyLog {
  const received: CicsAid[] = [];

  return {
    received,
    /**
     * Appends one dispatched AID to this log.
     * @param {CicsAid} aid - The canonical AID the handler was invoked with.
     * @returns {void} Nothing; the append is the observable effect.
     */
    record: (aid: CicsAid): void => {
      received.push(aid);
    },
  };
}

/**
 * Registers handlers for a set of AIDs with no legend text, standing for keyboard-only bindings.
 *
 * Assumptions: the label is OMITTED rather than invented. `usePfKeys.ts` documents an empty label as
 * a keyboard-only handler and `PfKeyBar.tsx` renders no control for one, which is exactly the shape
 * the baseline has where a key works without being painted —
 * `app/cbl/COUSR03C.cbl:L123-L125` binds `DFHPF12` while the row-24 literal of
 * `app/bms/COUSR03.bms` advertises only four actions. Supplying a made-up label instead would put a
 * control on the glass that the terminal never showed.
 * @param {readonly CicsAid[]} aids - The AIDs the notional screen binds.
 * @param {KeyLog} log - The log each handler records into.
 * @returns {PfKeyHandlerMap} A sparse handler map covering exactly those AIDs.
 */
function keyboardOnlyHandlers(aids: readonly CicsAid[], log: KeyLog): PfKeyHandlerMap {
  const handlers: Partial<Record<CicsAid, PfKeyHandlerEntry>> = {};

  for (const aid of aids) {
    handlers[aid] = { onInvoke: log.record };
  }

  return handlers;
}

/**
 * Registers handlers carrying the verbatim legend text a screen paints.
 *
 * Assumptions: the map is built by walking {@link CICS_AIDS} and looking each AID up in the supplied
 * labels, rather than by iterating the labels' own entries. `Object.entries` widens a key to
 * `string`, so that route would need a cast back to `CicsAid` — an assertion the compiler cannot
 * check and this tree forbids. Walking the domain keeps every key genuinely typed and, as a
 * by-product, produces the AID order `usePfKeys` itself builds bindings in.
 * @param {Readonly<Partial<Record<CicsAid, string>>>} labels - Verbatim legend text per AID, taken
 * from the message catalog.
 * @param {KeyLog} log - The log each handler records into.
 * @param {readonly CicsAid[]} [disabledAids] - AIDs whose handler is unavailable in this state.
 * @returns {PfKeyHandlerMap} A sparse handler map covering exactly the labelled AIDs.
 */
function legendHandlers(
  labels: Readonly<Partial<Record<CicsAid, string>>>,
  log: KeyLog,
  disabledAids: readonly CicsAid[] = [],
): PfKeyHandlerMap {
  const handlers: Partial<Record<CicsAid, PfKeyHandlerEntry>> = {};

  for (const aid of CICS_AIDS) {
    const label = labels[aid];

    if (label === undefined) {
      continue;
    }

    // WHY : Assumptions: `disabled` is always supplied rather than left absent when false, because
    //       `ui/tsconfig.json` sets `exactOptionalPropertyTypes` — under it an explicit `undefined`
    //       is not the same value as an omitted member, so a conditional spread would be the only
    //       other way to write this and it would read as though absence carried a meaning it does
    //       not. `usePfKeys` treats `false` and absent identically.
    handlers[aid] = { onInvoke: log.record, label, disabled: disabledAids.includes(aid) };
  }

  return handlers;
}

/**
 * Builds one render-ready descriptor, for the cases that exercise the bar as a pure component.
 *
 * Alternatives Considered: driving every bar case through `usePfKeys` as well. Rejected because the
 * descriptor array IS the bar's contract — `PfKeyBar.tsx` records that taking a per-screen array
 * rather than deciding a key set for itself is "the central design decision in this module" — and a
 * case that could only ever supply what the hook happens to produce could not show that the bar
 * renders what it is GIVEN. The hook-driven cases live in the dispatch suites, where the hook is the
 * subject.
 * @param {CicsAid} aid - The AID the control represents.
 * @param {string} label - The verbatim legend text, from the message catalog or the uniform set.
 * @param {boolean} [enabled] - Whether the key may be invoked in this state; defaults to available.
 * @param {PfKeyAction} [action] - The semantic action, defaulting to the shell's own submit verb for
 * the descriptors whose action no case inspects.
 * @returns {PfKeyBinding} One descriptor in the shape `PfKeyBar` consumes.
 */
function binding(
  aid: CicsAid,
  label: string,
  enabled = true,
  action: PfKeyAction = 'screen-defined',
): PfKeyBinding {
  return { aid, action, label, enabled };
}

/**
 * Presses one browser key by name, through a real DOM keyboard event.
 *
 * ⚠️ Alternatives Considered: `pressPfKey` from `ui/src/test/setup.ts`, which is the helper a screen
 * test uses and is deliberately NOT used for the alias cases. It derives the browser key by
 * inverting `KEYBOARD_KEY_TO_AID` while SKIPPING every member `PF_KEY_ALIASES` contributes, so it
 * presses `F1` for `PFK01` and can never press `F13` at all — which makes the alias half of the
 * contract unreachable through it. That skip is correct for its own purpose, since a screen wants
 * the canonical key; it just cannot express the claim these cases make. `pressPfKey` is still used
 * below where the canonical key is what is wanted, and for its documented rejection.
 *
 * Assumptions: an unrecognised `{Descriptor}` reaches the DOM as `key: descriptor`. The pinned
 * `@testing-library/user-event` 14.6.1 carries no function key in its default key map at all; its
 * `parseKeyDef` falls back to `{ key: 'Unknown', code: 'Unknown', key: descriptor }` for a
 * descriptor it does not know, so `{F13}` dispatches a keydown whose `key` is `'F13'`. That is
 * exactly the property `resolveAid` reads, and `pressPfKey` rests on the same fallback.
 * @param {HarnessRenderResult['user']} user - The operator from a render helper's result.
 * @param {string} key - The `KeyboardEvent.key` value to raise.
 * @returns {Promise<void>} Resolves once the event has been dispatched and settled.
 */
async function pressBrowserKey(user: HarnessRenderResult['user'], key: string): Promise<void> {
  await user.keyboard(`{${key}}`);
}

/**
 * The browser key name for one function-key ordinal.
 * @param {number} ordinal - The function key's number, from one.
 * @returns {string} The `KeyboardEvent.key` value, such as `F7`.
 */
function functionKeyName(ordinal: number): string {
  return `F${String(ordinal)}`;
}

/**
 * The AID name the copybook pairs with one function-key ordinal.
 *
 * Assumptions: the ordinal is padded to two digits, because `app/cpy/CVCRD01Y.cpy:L8-L19` spells the
 * condition names `PFK01` through `PFK12` — zero-padded — so `PFK1` would name nothing. Computing
 * the name from the ordinal is what keeps the assertion independent of the table it checks.
 * @param {number} ordinal - The function key's number, from one.
 * @returns {string} The AID name, such as `PFK07`.
 */
function pfKeyAidName(ordinal: number): string {
  return `PFK${String(ordinal).padStart(2, '0')}`;
}

/**
 * Inputs to {@link KeyProbe}.
 */
interface KeyProbeProps {
  /** The handler map the notional screen registers. */
  readonly handlers: PfKeyHandlerMap;
  /** Whether the screen reports an unmapped or disabled key through the message channel. */
  readonly reportsInvalidKey: boolean;
}

/**
 * A notional screen wired exactly as a real one is: hook, legend, message band.
 *
 * Purpose
 * -------
 * This is the smallest tree in which the whole contract is observable at once. `usePfKeys` installs
 * the document keydown listener and returns the bindings; `PfKeyBar` renders those bindings and
 * forwards a click back through the same `invoke`; `MessageBand` paints whatever the hook reports as
 * invalid. Nothing here is a stand-in — all three are the production modules.
 *
 * ⚠️ Assumptions: the rejection is held in React STATE and not in a module variable, and that is
 * load-bearing rather than idiomatic. `onInvalidKey` fires inside a DOM event handler; a mutation
 * that React cannot see does not schedule a render, so the band would keep painting nothing and a
 * case asserting on the message would fail while the hook was behaving perfectly. Holding it in
 * state is also what a real screen does with it.
 *
 * Assumptions: an entry field is rendered because ENTER is the one AID whose dispatch depends on
 * where the cursor is. `usePfKeys.ts` defers ENTER when the event's own target is an element a
 * browser activates on Enter — `ENTER_ACTIVATED_TARGET_SELECTOR` includes `button` — so a case that
 * pressed Enter while a legend control held focus would exercise the browser's activation of that
 * control rather than the screen's submit verb. Focusing a text input first reproduces the 3270
 * gesture, where Enter was pressed from a field.
 *
 * Assumptions: the message channel is switchable rather than always present, because whether a
 * screen has one at all is a measured property of the baseline and not a detail. Fourteen programs
 * move `CCDA-MSG-INVALID-KEY` on an unrecognised key; six — `COACTVWC`, `COACTUPC`, `COCRDLIC`,
 * `COCRDSLC`, `COCRDUPC` and `COTRTLIC` — emit no such message at all. Passing the reporter is how
 * the first fourteen are expressed and withholding it is how the other six are, so both shapes are
 * reachable without either being invented.
 * @param {KeyProbeProps} props - The probe's inputs, destructured below.
 * @param {PfKeyHandlerMap} props.handlers - The handler map the notional screen registers.
 * @param {boolean} props.reportsInvalidKey - Whether this screen has a message channel at all.
 * @returns {ReactElement} The probe's tree: an entry field, the legend and the message band.
 */
function KeyProbe({ handlers, reportsInvalidKey }: KeyProbeProps): ReactElement {
  const [rejection, setRejection] = useState<PfKeyRejection | null>(null);

  /**
   * Holds one reported rejection so the band can paint it.
   * @param {PfKeyRejection} reported - The invalid-key payload the hook raised.
   * @returns {void} Nothing; the state update is the observable effect.
   */
  function holdRejection(reported: PfKeyRejection): void {
    setRejection(reported);
  }

  const { bindings, invoke } = usePfKeys(
    handlers,
    // WHY : Assumptions: the reporter is supplied conditionally through a ternary rather than by
    //       spreading an object, because `exactOptionalPropertyTypes` makes an explicitly
    //       `undefined` member distinct from an absent one and `UsePfKeysOptions` declares
    //       `onInvalidKey` optional without admitting `undefined`.
    reportsInvalidKey ? { onInvalidKey: holdRejection } : {},
  );

  return (
    <div>
      <label htmlFor="probe-entry">Entry</label>
      <input id="probe-entry" defaultValue="" />
      <PfKeyBar keys={bindings} onInvoke={invoke} />
      <MessageBand message={rejection?.message ?? null} severity="error" />
    </div>
  );
}

/**
 * Renders one row of the notional browse.
 *
 * Assumptions: the row's own text is its React key, which is safe because each page's rows are
 * distinct strings by construction and no case re-orders them.
 * @param {string} row - The row's text, from one of the two page fixtures.
 * @returns {ReactElement} A list item carrying that text.
 */
function renderBrowseRow(row: string): ReactElement {
  return <li key={row}>{row}</li>;
}

/**
 * Renders one binding's resolved semantic action so a case can read it out of the DOM.
 * @param {PfKeyBinding} item - One binding from the hook's result.
 * @returns {ReactElement} A list item, identified by AID, carrying that binding's action.
 */
function renderBindingAction(item: PfKeyBinding): ReactElement {
  return (
    <li key={item.aid} data-testid={`action-${item.aid}`}>
      {item.action}
    </li>
  );
}

/**
 * The text one legend control carries.
 * @param {HTMLElement} control - One control taken from the legend landmark.
 * @returns {string} Its text content, or the empty string when it holds none.
 */
function controlText(control: HTMLElement): string {
  return control.textContent ?? '';
}

/**
 * Reports whether the shared shell fixes a meaning for one attention identifier.
 * @param {CicsAid} aid - One member of the AID domain.
 * @returns {boolean} `true` when {@link EXPECTED_DEFAULT_ACTIONS} names an action for it.
 */
function hasADefaultAction(aid: CicsAid): boolean {
  return EXPECTED_DEFAULT_ACTIONS[aid] !== undefined;
}

/**
 * Hands a promise's resolver to the task queue rather than calling it immediately.
 * @param {() => void} resolve - The resolver of the promise being scheduled.
 * @returns {void} Nothing; the resolution happens once the current task has finished.
 */
function scheduleTaskResolution(resolve: () => void): void {
  setTimeout(resolve, 0);
}

/**
 * A promise that settles on a later task, not in the microtask queue of the current one.
 *
 * ⚠️ Refactoring Rationale: the notional browse's reader awaits this before answering, and the delay
 * is load-bearing rather than cosmetic. `renderWithProviders` is asynchronous, so awaiting it yields
 * the microtask queue — and a reader that answered from an already-resolved promise would settle the
 * browse inside that same gap, after the render had committed and before the case had run its first
 * statement. React reports the resulting state update as one made outside `act`, and the case then
 * races two states. Deferring by a task moves the settle inside the wait each case performs, where it
 * is observed rather than raced. It is also the more truthful reader: a real one answers over a
 * transport and never in the same microtask as the render that asked.
 *
 * Alternatives Considered: wrapping each wait in an explicit empty `act` call to flush the queue.
 * Rejected because it treats the symptom at every call site rather than the cause at the one place
 * the timing is decided, and because an empty `act` body documents nothing about why it is there.
 * @returns {Promise<void>} Resolves once the task queue has been reached.
 */
function nextTask(): Promise<void> {
  return new Promise<void>(scheduleTaskResolution);
}

/**
 * Rows the notional browse's opening page carries.
 *
 * Assumptions: the row text is arbitrary and is deliberately not transcribed from any record layout,
 * because these cases assert nothing about what a browse displays — only about when its paging keys
 * are available. Borrowing a real card or transaction value would suggest a fidelity claim that is not
 * being made here, and each screen's own test file makes that claim against its own contract.
 */
const OPENING_PAGE_ROWS: readonly string[] = ['opening row one', 'opening row two'];

/** Rows the notional browse's second page carries, distinct so a landed read is observable. */
const FURTHER_PAGE_ROWS: readonly string[] = ['further row one', 'further row two'];

/**
 * Inputs to {@link BrowseProbe}.
 */
interface BrowseProbeProps {
  /** What the opening page's envelope reports about a further page existing. */
  readonly hasNextOnOpeningPage: boolean;
}

/**
 * A notional browse screen whose paging keys take their availability from the keyset envelope.
 *
 * Purpose
 * -------
 * The paging half of the contract cannot be shown by asserting on a boolean this file computed: the
 * claim is that the KEYS follow the envelope. So the real `usePagedQuery` is driven by a stub reader
 * answering with the real four-member envelope from `pageResponse`, and PF7 and PF8 take their
 * `disabled` state from that hook's `hasPrev` and `hasNext`.
 *
 * ⚠️ Refactoring Rationale: paging is keyset and never offset, and the reason is that the baseline
 * browse state ALREADY was a keyset cursor rather than a page position.
 * `app/cbl/COCRDLIC.cbl:L229-L244` holds, in the COMMAREA it echoes to the terminal, a last-key
 * pair, a first-key pair, a screen number, a last-page-displayed flag and a next-page-exists
 * indicator — and it sets that indicator by reading one record more than fits rather than by
 * counting. Offset pagination was rejected because under concurrent inserts it skips and repeats
 * rows: a row inserted before the current offset shifts every later row down, so the next page
 * re-shows one and drops another. Browse-by-key cannot do that, because it asks for the rows after a
 * named key rather than the rows after a count. Reproducing the reference's observable behaviour is
 * the requirement; reproducing it with a mechanism that changes under concurrency would not.
 *
 * ⚠️ Assumptions: `hasPrev` is derived on the CLIENT and is not a member of the envelope.
 * `ui/src/api/types.ts` declares `PageResponse` with exactly four members — `items`, `firstKey`,
 * `lastKey` and `hasNext` — and `usePagedQuery` computes backward availability at its own L1076 from
 * the page ordinal and the leading cursor. That mirrors the reference, which never asks the file
 * whether an earlier page exists: `app/cbl/COCRDLIC.cbl:L901-L903` refuses the backward step from
 * `CA-FIRST-PAGE` alone, with no backward read issued.
 *
 * Assumptions: the stub reports a further page only for the OPENING read, so one forward step
 * reaches a terminal page and the two flags are observable in both of their combinations from one
 * mounted probe. `pageSize` is two because the arity is irrelevant to this claim and a smaller page
 * settles faster; the measured arities differ per screen and `usePagedQuery` refuses to default one
 * precisely because no single value is right.
 * @param {BrowseProbeProps} props - The probe's inputs, destructured below.
 * @param {boolean} props.hasNextOnOpeningPage - What the opening envelope reports.
 * @returns {ReactElement} The legend for the two paging keys, plus the page ordinal.
 */
function BrowseProbe({ hasNextOnOpeningPage }: BrowseProbeProps): ReactElement {
  /**
   * Answers one page in the four-member envelope shape every contract publishes.
   *
   * Assumptions: the two pages carry DIFFERENT rows, and that is what makes a case able to wait for a
   * read to land rather than guess. The paging flags are only meaningful once a read has resolved, so
   * a case asserts after the row belonging to the page it expects has appeared; identical rows would
   * leave it nothing to wait for on the second read and it would assert against the first page's
   * flags.
   * @param {PagedQueryRequest} request - The position to read from and the direction to read in.
   * @returns {Promise<PageResponse<string>>} One page, built by the shared envelope helper.
   */
  async function fetchPage(request: PagedQueryRequest): Promise<PageResponse<string>> {
    const openingRead = request.cursor === null;
    const page = pageResponse(openingRead ? OPENING_PAGE_ROWS : FURTHER_PAGE_ROWS, {
      hasNext: openingRead && hasNextOnOpeningPage,
    });

    await nextTask();

    return page;
  }

  const browse = usePagedQuery<string>({ pageSize: 2, fetchPage });

  /**
   * Steps to the page before the one on display, which is PF7's action.
   * @returns {void} Nothing; the browse publishes the outcome on a later render.
   */
  function pageBackward(): void {
    browse.prevPage();
  }

  /**
   * Steps to the page after the one on display, which is PF8's action.
   * @returns {void} Nothing; the browse publishes the outcome on a later render.
   */
  function pageForward(): void {
    browse.nextPage();
  }

  const { bindings, invoke } = usePfKeys({
    // WHY : Assumptions: the labels come from `UNIFORM_PF_KEY_LABELS` rather than being typed here.
    //       `PfKeyBar.tsx` owns those three because `F7=Backward` and `F8=Forward` are
    //       byte-identical everywhere they appear in the seventeen measured legends, so a screen
    //       that restated them would create a second place for one string to live.
    PFK07: {
      onInvoke: pageBackward,
      label: UNIFORM_PF_KEY_LABELS.PFK07,
      disabled: !browse.hasPrev,
    },
    PFK08: {
      onInvoke: pageForward,
      label: UNIFORM_PF_KEY_LABELS.PFK08,
      disabled: !browse.hasNext,
    },
  });

  return (
    <div>
      <PfKeyBar keys={bindings} onInvoke={invoke} />
      <span data-testid="page-ordinal">{String(browse.pageNumber)}</span>
      <ul>{browse.items.map(renderBrowseRow)}</ul>
    </div>
  );
}

/**
 * Every control the function-key legend currently paints, in the order it paints them.
 *
 * Assumptions: the bar is located by its landmark role and its own exported accessible name rather
 * than by a test identifier. `PfKeyBar.tsx` renders a `nav` carrying
 * {@link PF_KEY_BAR_REGION_LABEL}, and the role is the property assistive technology acts on, so it
 * is the one worth regressing against — a query by test identifier would pass just as well against a
 * `div` that announces nothing. Scoping the button query inside that landmark is what makes "and no
 * more" assertable: a control rendered elsewhere in the tree cannot be miscounted as a legend key.
 * @returns {HTMLElement[]} The legend's controls, in document order.
 */
function legendControls(): HTMLElement[] {
  const legend = screen.getByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL });

  return within(legend).getAllByRole('button');
}

/**
 * The verbatim text each legend control carries, in the order the legend paints them.
 *
 * Assumptions: `textContent` rather than the accessible name, because the accessible-name computation
 * trims and collapses whitespace while several measured legends carry padding and doubled spaces that
 * are content — `app/bms/COACTVW.bms:L373` paints `'  F3=Exit '` and `app/bms/COUSR02.bms:L163`
 * separates its parts with two spaces. Reading the DOM text is what lets a case assert those bytes
 * survived; reading the accessible name would accept a screen that had normalised them away.
 * @returns {string[]} Each control's text, in document order.
 */
function legendLabels(): string[] {
  return legendControls().map(controlText);
}

/**
 * Renders one notional screen with a message channel, which is the fourteen-program shape.
 * @param {PfKeyHandlerMap} handlers - The handler map the notional screen registers.
 * @returns {Promise<HarnessRenderResult>} The render result, with the operator attached.
 */
async function renderReportingScreen(handlers: PfKeyHandlerMap): Promise<HarnessRenderResult> {
  return renderWithProviders(<KeyProbe handlers={handlers} reportsInvalidKey />);
}

/**
 * Pins the domain at the copybook's sixteen condition names, in their declared order.
 *
 * Assumptions: sixteen and not thirteen. Three of them — the members of
 * {@link AIDS_NO_PROGRAM_ACTS_ON} — have no browser key and no program that acts on them, and they
 * remain in the domain anyway because `CCARD-AID` declares them. Narrowing the union to the keys
 * that work would be a tidier type and a less faithful one: the copybook's `88`-level set is the
 * contract, and a later reader meeting `CLEAR` in the union is meant to find this file explaining
 * why it is there rather than concluding the union is stale.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function carriesTheSixteenDeclaredConditionNames(): void {
  expect([...CICS_AIDS]).toEqual([
    'ENTER',
    'CLEAR',
    'PA1',
    'PA2',
    'PFK01',
    'PFK02',
    'PFK03',
    'PFK04',
    'PFK05',
    'PFK06',
    'PFK07',
    'PFK08',
    'PFK09',
    'PFK10',
    'PFK11',
    'PFK12',
  ]);
}

/**
 * Registers the cases covering the AID domain, from app/cpy/CVCRD01Y.cpy L3-L19.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function aidDomainCases(): void {
  it(
    'carries the sixteen condition names CCARD-AID declares',
    carriesTheSixteenDeclaredConditionNames,
  );
}

describe('the AID domain, from app/cpy/CVCRD01Y.cpy L3-L19', aidDomainCases);

/**
 * Pins the first clause of the copybook's `EVALUATE`.
 *
 * Assumptions: `Enter` is the browser key rather than `Return`, because that is the
 * `KeyboardEvent.key` value a browser reports for the key in question; the terminal name and the
 * web name coincide here, which is why this is the one clause that needs no translation.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function normalisesEnterKey(): void {
  expect(resolveAid('Enter')).toBe('ENTER');
}

/**
 * Pins all twelve direct clauses of the second range, arithmetically rather than by table copy.
 *
 * Assumptions: both sides of each comparison are computed from the ordinal — the key name by
 * {@link functionKeyName} and the AID name by {@link pfKeyAidName} — so this checks the imported
 * table against the copybook's RULE instead of against a second copy of the table. A single
 * mis-transcribed `WHEN` in `app/cpy/CSSTRPFY.cpy:L30-L53`, such as `DFHPF9` setting `PFK10`,
 * fails here; a copied table would have agreed with it.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function normalisesTheDirectFunctionKeys(): void {
  for (let ordinal = 1; ordinal <= DIRECT_PF_KEY_COUNT; ordinal += 1) {
    expect(resolveAid(functionKeyName(ordinal))).toBe(pfKeyAidName(ordinal));
  }
}

/**
 * Pins the twelve alias clauses as a relation between the two banks of function keys.
 *
 * ⚠️ Assumptions: this is the headline contract. `app/cpy/CSSTRPFY.cpy:L54-L77` sets the SAME flag
 * for `DFHPF13` as for `DFHPF1`, for `DFHPF14` as for `DFHPF2`, and so on to `DFHPF24` and
 * `DFHPF12` — twelve `WHEN` clauses that fold the second bank onto the first rather than extending
 * the domain. The relation is asserted here rather than the pairs being listed, so the case states
 * the copybook's rule: alias ordinal n resolves to whatever base ordinal n − {@link
 * PF_KEY_ALIAS_OFFSET} resolves to.
 *
 * Assumptions: the alias table's own size is checked as well, because the relation alone would
 * still hold if a thirteenth entry were added or one were dropped — twelve is the count of `WHEN`
 * clauses in that range and the count of PF condition names in `app/cpy/CVCRD01Y.cpy:L8-L19`.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function foldsTheAliasBankOntoTheDirectBank(): void {
  expect(Object.keys(PF_KEY_ALIASES)).toHaveLength(DIRECT_PF_KEY_COUNT);

  for (let ordinal = 1; ordinal <= DIRECT_PF_KEY_COUNT; ordinal += 1) {
    const aliasName = functionKeyName(ordinal + PF_KEY_ALIAS_OFFSET);

    expect(resolveAid(aliasName)).toBe(resolveAid(functionKeyName(ordinal)));
    expect(resolveAid(aliasName)).toBe(pfKeyAidName(ordinal));
  }
}

/**
 * Proves the folding survives the whole dispatch path, not merely the lookup.
 *
 * ⚠️ Assumptions: every pair is driven through a REAL keydown rather than through the lookup, and
 * the two are not the same claim. The lookup case above shows the table folds; this one shows that
 * a key press folded by the table reaches the same registered handler, which is what an operator
 * observes and what a regression anywhere between the listener and `invoke` would break. All
 * twelve base AIDs are registered on one probe, each recording into one log, so a pair that
 * diverged would show up as two different AIDs in the log rather than as a missing call.
 *
 * Trade-offs: this is one case making twenty-four key presses rather than twelve cases making two
 * each. A parametrised case per pair would name the failing pair in the runner's output, which is
 * the real cost accepted here; against it, the twelve share one mounted probe and one log, and the
 * assertion that matters is a claim about the SET — that every pair folds and none is left
 * unfolded — which reads as one sentence and would read as twelve identical ones.
 * @returns {Promise<void>} Resolves once every pair has been pressed and asserted.
 */
async function dispatchesEachAliasToItsCounterpartHandler(): Promise<void> {
  const log = createKeyLog();
  const baseAids = CICS_AIDS.filter(
    /**
     * Selects the twelve PF members of the domain, leaving ENTER and the unbound three out.
     * @param {CicsAid} aid - One member of the domain.
     * @returns {boolean} `true` for `PFK01` through `PFK12`.
     */
    (aid: CicsAid): boolean => aid.startsWith('PFK'),
  );
  const { user } = await renderWithProviders(
    <KeyProbe handlers={keyboardOnlyHandlers(baseAids, log)} reportsInvalidKey />,
  );

  for (let ordinal = 1; ordinal <= DIRECT_PF_KEY_COUNT; ordinal += 1) {
    await pressBrowserKey(user, functionKeyName(ordinal + PF_KEY_ALIAS_OFFSET));
    await pressBrowserKey(user, functionKeyName(ordinal));
  }

  /*
   * WHY : Assumptions: each ordinal contributes its AID TWICE, once for the alias press and once
   *       for the base press, and the two are adjacent because the loop above presses them in
   *       that order. Building the expectation this way asserts the ORDER as well as the
   *       membership, so an implementation that folded the alias correctly but dispatched it at
   *       the wrong moment — on key-up, say, or once per pair — would still fail.
   */
  const expected: string[] = [];

  for (let ordinal = 1; ordinal <= DIRECT_PF_KEY_COUNT; ordinal += 1) {
    expected.push(pfKeyAidName(ordinal), pfKeyAidName(ordinal));
  }

  expect(log.received).toEqual(expected);
}

/**
 * Registers the cases covering normalisation, from app/cpy/CSSTRPFY.cpy L21-L78.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function normalisationCases(): void {
  it('normalises Enter to ENTER, per L22-L23', normalisesEnterKey);
  it(
    'normalises F1 through F12 to PFK01 through PFK12, per L30-L53',
    normalisesTheDirectFunctionKeys,
  );
  it(
    'folds F13 through F24 onto the same AIDs as F1 through F12, per L54-L77',
    foldsTheAliasBankOntoTheDirectBank,
  );
  it(
    'dispatches each F13-F24 key to the same handler as its F1-F12 counterpart',
    dispatchesEachAliasToItsCounterpartHandler,
  );
}

describe('normalisation, from app/cpy/CSSTRPFY.cpy L21-L78', normalisationCases);

/**
 * Pins the three unbound members as exactly the members with no browser key.
 *
 * ⚠️ Assumptions: the left side is DERIVED — every member of the domain that no entry of
 * `KEYBOARD_KEY_TO_AID` maps to — so the case discovers the unbound set rather than being told it,
 * and inventing a browser key for any of the three would fail here immediately. That is the point:
 * `usePfKeys.ts` records that measured usage of these three across all 21 online programs is zero,
 * and the exhaustive search behind {@link AIDS_NO_PROGRAM_ACTS_ON} confirms the six corresponding
 * tokens appear in no program in `app/**` at all. The natural instinct on meeting a normalised key
 * that does nothing is to wire it up, and this assertion is what stops that being done quietly.
 * @returns {void} Nothing; the assertion either passes or fails the case.
 */
function areTheOnlyMembersWithNoBrowserKey(): void {
  const mappedAids = new Set(Object.values(KEYBOARD_KEY_TO_AID));
  const unmapped = CICS_AIDS.filter(
    /**
     * Selects the domain members no browser key normalises to.
     * @param {CicsAid} aid - One member of the domain.
     * @returns {boolean} `true` when no entry of the browser-key table yields this AID.
     */
    (aid: CicsAid): boolean => !mappedAids.has(aid),
  );

  expect(unmapped).toEqual(AIDS_NO_PROGRAM_ACTS_ON);
}

/**
 * Confirms the shared key-press helper refuses to raise them, rather than raising them wrongly.
 *
 * Assumptions: the rejection is the documented behaviour of `pressPfKey` in
 * `ui/src/test/setup.ts`, which throws for an AID with no browser key on the ground that a case
 * reaching for one is testing a path the application does not have. Asserting it here fixes that as
 * a contract instead of an implementation detail, so a later edit that made the helper fall back to
 * some invented key would fail in this file rather than silently giving twenty-one screen tests a
 * key the baseline never had.
 *
 * Assumptions: the match is against the helper's own diagnostic wording, which is test scaffolding
 * rather than baseline text, so the message-catalog rule that governs user-visible strings does not
 * reach it.
 * @returns {Promise<void>} Resolves once each of the three has been shown to reject.
 */
async function cannotBePressedThroughTheSharedHelper(): Promise<void> {
  const log = createKeyLog();
  const { user } = await renderWithProviders(
    <KeyProbe handlers={keyboardOnlyHandlers(AIDS_NO_PROGRAM_ACTS_ON, log)} reportsInvalidKey />,
  );

  for (const aid of AIDS_NO_PROGRAM_ACTS_ON) {
    await expect(pressPfKey(user, aid)).rejects.toThrow(/no browser key/);
  }

  expect(log.received).toEqual([]);
}

/**
 * Proves a key press that spells one of them reaches nothing and says nothing.
 *
 * ⚠️ Assumptions: silence is the whole assertion, and it has two halves that are easy to conflate.
 * No handler runs — which is the obvious half — and no invalid-key message is raised either, which
 * is not. `usePfKeys` normalises before it looks a handler up, so a key that yields no AID leaves
 * the listener before the rejection path exists; only a RECOGNISED AID with no handler is reported.
 * That distinction is the reason the probe here registers handlers for all three: even with a
 * handler waiting, the key cannot reach it, so the case cannot pass merely because nothing was
 * bound.
 * @returns {Promise<void>} Resolves once the three keys have been pressed and the silence asserted.
 */
async function raiseNeitherHandlerNorMessage(): Promise<void> {
  const log = createKeyLog();
  const { user } = await renderWithProviders(
    <KeyProbe handlers={keyboardOnlyHandlers(AIDS_NO_PROGRAM_ACTS_ON, log)} reportsInvalidKey />,
  );

  for (const keyName of UNBOUND_BROWSER_KEY_NAMES) {
    await pressBrowserKey(user, keyName);
  }

  expect(log.received).toEqual([]);
  expect(screen.queryByText(INVALID_KEY_PRESSED.trimEnd())).toBeNull();
}

/**
 * Registers the cases covering the keys the copybook normalises that no program acts on.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function unboundKeyCases(): void {
  it(
    'are the only members of the domain with no browser key at all',
    areTheOnlyMembersWithNoBrowserKey,
  );
  it('cannot be pressed through the shared helper', cannotBePressedThroughTheSharedHelper);
  it(
    'invoke no handler and raise no message when their key names are pressed',
    raiseNeitherHandlerNorMessage,
  );
}

describe('the keys the copybook normalises that no program acts on', unboundKeyCases);

/**
 * Pins the sentence and its declared field width against the catalog entry.
 *
 * ⚠️ Alternatives Considered: retyping the sentence into this file as a string literal. Rejected
 * because it defeats the assertion. `usePfKeys.ts` imports `INVALID_KEY_PRESSED` from the catalog
 * and puts it on the rejection; if this case held its own copy, then a paraphrase introduced in the
 * catalog would be matched by a paraphrase introduced here and the case would pass while fidelity
 * was lost — the test would be asserting that the hook agrees with the TEST rather than with the
 * source. Comparing the hook's own constant against the catalog entry it is defined from, and the
 * catalog entry against the copybook's declared width, is what makes the chain end at
 * `app/cpy/CSMSG01Y.cpy` instead of at this file.
 *
 * ⚠️ Assumptions: the declared width is 50 and the literal is SHORTER than that, so the two are
 * asserted separately and the length is deliberately not asserted equal to the width.
 * `CCDA-MSG-INVALID-KEY` is declared `PIC X(50)` at L20 while its `VALUE` at L21 is 49 characters
 * — COBOL supplies the fiftieth as a pad at run time. An `expect(text).toHaveLength(declaredWidth)`
 * therefore fails against the correct value, which is exactly the trap this note exists to keep the
 * next reader out of; the meaningful invariant is that the literal FITS its field.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function isTheCatalogEntryAtItsDeclaredWidth(): void {
  expect(INVALID_KEY_PRESSED).toBe(COMMON_MESSAGES.INVALID_KEY.text);
  expect(COMMON_MESSAGES.INVALID_KEY.declaredWidth).toBe(50);
  expect(INVALID_KEY_PRESSED.length).toBeLessThanOrEqual(COMMON_MESSAGES.INVALID_KEY.declaredWidth);
  expect(COMMON_MESSAGES.INVALID_KEY.source).toEqual({
    file: 'app/cpy/CSMSG01Y.cpy',
    lines: [21],
  });
}

/**
 * Proves an unmapped key raises that sentence, verbatim, through the screen's message channel.
 *
 * Assumptions: this is the shape of the FOURTEEN programs that move `CCDA-MSG-INVALID-KEY` on an
 * unrecognised attention identifier — `COSGN00C` at L93, `COMEN01C` at L101, `COADM01C` at L105,
 * `COBIL00C` at L140, `CORPT00C` at L193, `COTRN00C` at L132, `COTRN01C` at L130, `COTRN02C` at
 * L150, `COUSR00C` at L135, `COUSR01C` at L101, `COUSR02C` at L129, `COUSR03C` at L128,
 * `COPAUS0C` at L248 and `COPAUS1C` at L196 — every one of them from the `WHEN OTHER` arm of an
 * `EVALUATE EIBAID`. F5 is pressed because the probe registers only PF3, so F5 is a recognised AID
 * with no handler, which is precisely the condition that arm answers.
 *
 * Assumptions: the rendered text is matched with the trailing pad removed, because
 * `expectVerbatimMessage` normalises the element's BOUNDARY whitespace — introduced by markup —
 * while preserving internal runs. The padding is trimmed from the expectation rather than from the
 * catalog, so the doubled spaces inside the sentence are still compared character for character.
 * @returns {Promise<void>} Resolves once the key has been pressed and the band asserted.
 */
async function isRaisedVerbatimByAnUnboundKey(): Promise<void> {
  const log = createKeyLog();
  const { user } = await renderReportingScreen(keyboardOnlyHandlers(['PFK03'], log));

  await pressBrowserKey(user, functionKeyName(5));

  expect(log.received).toEqual([]);
  expectVerbatimMessage(INVALID_KEY_PRESSED.trimEnd());
}

/**
 * Proves the message channel is the screen's to supply, which is how the six silent screens work.
 *
 * ⚠️ Assumptions: six programs emit NO invalid-key message at all — `COACTVWC`, `COACTUPC`,
 * `COCRDLIC`, `COCRDSLC`, `COCRDUPC` and `COTRTLIC`, verified by search to contain zero references
 * to `CCDA-MSG-INVALID-KEY` — so their screens are `accountView`, `accountUpdate`, `cardList`,
 * `cardDetail`, `cardUpdate` and `refTypeList`. Asserting the sentence on any of those six would be
 * a fabrication, so what is asserted instead is the MECHANISM that lets them be silent: the
 * reporter is an option, and a screen that supplies none gets no message. That is a claim about the
 * hook, which is this file's subject, and it leaves each of the six screens' own silence to its own
 * test file to assert on its own terms.
 *
 * Assumptions: the key press must still be refused — nothing dispatched — because silence about an
 * unmapped key is not the same as accepting it. `COACTUPC` shows the reference's own version of
 * that distinction at L905-L916, where an unaccepted key is reduced to a screen refresh rather than
 * either running something or reporting an error.
 * @returns {Promise<void>} Resolves once the key has been pressed and the silence asserted.
 */
async function isAbsentWithoutAMessageChannel(): Promise<void> {
  const log = createKeyLog();
  const { user } = await renderWithProviders(
    <KeyProbe handlers={keyboardOnlyHandlers(['PFK03'], log)} reportsInvalidKey={false} />,
  );

  await pressBrowserKey(user, functionKeyName(5));

  expect(log.received).toEqual([]);
  expect(screen.queryByText(INVALID_KEY_PRESSED.trimEnd())).toBeNull();
}

/**
 * Keeps the shared sentence distinct from the one program that words it differently.
 *
 * ⚠️ Assumptions: `app/app-transaction-type-db2/cbl/COTRTUPC.cbl:L193-L194` declares its own
 * `88 WS-INVALID-KEY-PRESSED VALUE 'Invalid key pressed'` — no full stop, no "Please see below",
 * no ellipsis, and a `PIC X(75)` field rather than `PIC X(50)`. It is therefore a different string
 * in a different field, belonging to the `refTypeEdit` screen, and `refTypeEdit`'s own test file is
 * where that screen's wording is asserted. This case exists so the two files cannot drift into
 * contradicting each other: it asserts only that the two catalog entries are NOT the same value,
 * which is the fact a later edit merging them would break.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function staysDistinctFromTheTransactionTypeWording(): void {
  const transactionTypeUpdateWording = STATUS_MESSAGES.COTRTUPC.WS_INVALID_KEY_PRESSED;

  expect(transactionTypeUpdateWording.text).not.toBe(INVALID_KEY_PRESSED);
  expect(transactionTypeUpdateWording.declaredWidth).not.toBe(
    COMMON_MESSAGES.INVALID_KEY.declaredWidth,
  );
  expect(transactionTypeUpdateWording.line).toBe(194);
}

/**
 * Registers the cases covering the unmapped-key message, from app/cpy/CSMSG01Y.cpy L20-L21.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function unmappedKeyMessageCases(): void {
  it(
    'is the catalog entry the copybook declares, at its declared width',
    isTheCatalogEntryAtItsDeclaredWidth,
  );
  it(
    'is raised verbatim by a recognised key the screen has not bound',
    isRaisedVerbatimByAnUnboundKey,
  );
  it(
    'is absent entirely on a screen that supplies no message channel',
    isAbsentWithoutAMessageChannel,
  );
  it(
    'is not the different sentence COTRTUPC declares for itself',
    staysDistinctFromTheTransactionTypeWording,
  );
}

describe('the unmapped-key message, from app/cpy/CSMSG01Y.cpy L20-L21', unmappedKeyMessageCases);

/**
 * Proves a clicked control and the corresponding key press dispatch identically.
 *
 * ⚠️ Alternatives Considered: exercising the contract by clicking the legend's controls alone.
 * Rejected because the 3270 original had no pointer at all, so the keyboard binding is the
 * fidelity-bearing path and the buttons are the additive one. A click-only case would stay green
 * with every keyboard binding in the application broken — which is the regression this file exists
 * to make catchable — and keyboard operability here is a fidelity requirement rather than an
 * accessibility extra, sitting above it in the design-system compliance order.
 *
 * Assumptions: the equivalence is asserted through the same `invoke` both paths use.
 * `PfKeyBar.tsx` forwards a click to the caller's dispatch rather than holding logic of its own, so
 * what this case pins is that the forwarding reaches the handler the key reaches, with the same
 * AID, in the same log.
 * @returns {Promise<void>} Resolves once both paths have been driven and compared.
 */
async function clickAndKeyPressReachTheSameHandler(): Promise<void> {
  const log = createKeyLog();
  const { user } = await renderReportingScreen(
    legendHandlers({ PFK03: MAIN_MENU_KEY_LABELS.PFK03 }, log),
  );

  await user.click(screen.getByRole('button', { name: MAIN_MENU_KEY_LABELS.PFK03 }));
  await pressBrowserKey(user, functionKeyName(3));

  expect(log.received).toEqual(['PFK03', 'PFK03']);
}

/**
 * Pins ENTER's one documented departure from that equivalence.
 *
 * ⚠️ Assumptions: ENTER is the only member of the browser-key table that an element consumes
 * natively, and `usePfKeys.ts` defers it when the event's own target is one of those elements —
 * `ENTER_ACTIVATED_TARGET_SELECTOR` lists `button` among them. So pressing Enter while a legend
 * control holds focus activates THAT CONTROL, and pressing it from a field runs the screen's submit
 * verb. Both are asserted here rather than only the second, because the first is what a reader
 * discovers by accident when a case that clicks a button and then presses Enter records the clicked
 * key twice.
 *
 * Assumptions: the field is focused by clicking it, so focus is established the way a user
 * establishes it. A text input is deliberately absent from that selector, because Enter in a field
 * IS the 3270 submit gesture the shell must claim.
 *
 * Trade-offs: this makes the click-versus-keypress equivalence a claim about function keys plus
 * ENTER-from-a-field, rather than an unqualified one. Stating the qualification is the honest
 * option: an unqualified claim would be false, and a case asserting it would either fail or be
 * written to avoid the focus condition that falsifies it.
 * @returns {Promise<void>} Resolves once both focus conditions have been driven.
 */
async function enterFollowsWhereTheCursorSits(): Promise<void> {
  const log = createKeyLog();
  const { user } = await renderReportingScreen(
    legendHandlers({ ENTER: MAIN_MENU_KEY_LABELS.ENTER, PFK03: MAIN_MENU_KEY_LABELS.PFK03 }, log),
  );

  await user.click(screen.getByLabelText('Entry'));
  await pressBrowserKey(user, 'Enter');

  expect(log.received).toEqual(['ENTER']);

  await user.click(screen.getByRole('button', { name: MAIN_MENU_KEY_LABELS.PFK03 }));
  await pressBrowserKey(user, 'Enter');

  expect(log.received).toEqual(['ENTER', 'PFK03', 'PFK03']);
}

/**
 * Pins the second documented asymmetry, which is the one an unavailable key produces.
 *
 * ⚠️ Assumptions: a disabled control cannot fire a click, so the click reaches no dispatch and
 * nothing is reported; the key press does reach dispatch, finds the handler disabled and reports a
 * rejection carrying the baseline's own sentence. `PfKeyBar.tsx` records that asymmetry as
 * intended: only the key path exists in the baseline, so it is the one that must keep reporting the
 * way the source's message channel does, while for an additive control inertness is the stronger
 * feedback because it is visible before the user commits.
 *
 * Assumptions: the rejection's `reason` is asserted as well as its presence, because `unmapped` and
 * `disabled` are different findings that produce the same sentence — the first means the screen
 * never bound the key, the second that it bound it and the current state withholds it. A case
 * asserting only the sentence would pass if the two were confused.
 * @returns {Promise<void>} Resolves once both paths have been driven against the disabled key.
 */
async function aWithheldKeyIsInertToAClickAndReportsToAKey(): Promise<void> {
  const log = createKeyLog();
  const reported: PfKeyRejection[] = [];

  /**
   * Records one reported rejection for inspection.
   * @param {PfKeyRejection} rejection - The invalid-key payload the hook raised.
   * @returns {void} Nothing; the recording is the observable effect.
   */
  function holdRejection(rejection: PfKeyRejection): void {
    reported.push(rejection);
  }

  /**
   * A screen whose save key is bound but withheld by its current state.
   * @returns {ReactElement} The legend for that one key.
   */
  function WithheldSaveScreen(): ReactElement {
    const { bindings, invoke } = usePfKeys(
      legendHandlers({ PFK05: USER_DELETE_KEY_LABELS.PFK05 }, log, ['PFK05']),
      { onInvalidKey: holdRejection },
    );

    return <PfKeyBar keys={bindings} onInvoke={invoke} />;
  }

  const { user } = await renderWithProviders(<WithheldSaveScreen />);
  const control = screen.getByRole('button', { name: USER_DELETE_KEY_LABELS.PFK05 });

  expect(control).toBeDisabled();

  await user.click(control);

  expect(log.received).toEqual([]);
  expect(reported).toEqual([]);

  await pressBrowserKey(user, functionKeyName(5));

  expect(log.received).toEqual([]);
  expect(reported).toHaveLength(1);
  expect(reported[0]).toEqual({
    aid: 'PFK05',
    message: INVALID_KEY_PRESSED,
    reason: 'disabled',
    severity: 'error',
  });
}

/**
 * Registers the cases covering both activation paths.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function activationPathCases(): void {
  it(
    'a click on a legend control and its key press reach the same handler',
    clickAndKeyPressReachTheSameHandler,
  );
  it(
    'ENTER runs the screen verb from a field and activates a focused legend control',
    enterFollowsWhereTheCursorSits,
  );
  it(
    'a disabled key is inert to a click and reports the message to a key press',
    aWithheldKeyIsInertToAClickAndReportsToAKey,
  );
}

describe('both activation paths', activationPathCases);

/*
 * WHY : ⚠️ Assumptions: there is NO uniform key set, and a bar that assumed one could not express
 *       the population. The measured row-24 legends differ on every axis such a bar would have to
 *       fix: `app/bms/COACTVW.bms:L373` paints ONE key, `'  F3=Exit '`;
 *       `app/bms/COACTUP.bms:L497,L502,L507` paints three across three separate fields;
 *       `app/app-transaction-type-db2/bms/COTRTLI.bms:L316,L321,L326,L331,L336` paints five, and
 *       they are F2, F3, F7, F8 and F10 — a set that shares only F3 with the shared shell's seven;
 *       and `app/app-transaction-type-db2/bms/COTRTUP.bms:L115,L120,L125,L130,L135` paints ENTER,
 *       F3, F4, F5, F6 and F12. The wording diverges too: `app/bms/COUSR02.bms:L163` paints F3 as
 *       `Save&&Exit`, which contradicts the otherwise-reliable reading that F3 means "go back". So
 *       the shape is the caller's to state, and these cases assert that the bar renders what it is
 *       given rather than what it might have assumed.
 */

/**
 * Proves the bar renders exactly the descriptors supplied, in order, and no others.
 *
 * Assumptions: the order asserted is the order supplied, because `PfKeyBar.tsx` documents that it
 * sorts nothing — all seventeen measured legends already read in ascending AID order and
 * `usePfKeys` builds its bindings by walking that same order, so re-sorting could only diverge from
 * the legend's reading order. Tab order therefore follows the legend by construction, which is why
 * asserting document order here is asserting something a user can perceive.
 * @returns {Promise<void>} Resolves once the legend has been rendered and counted.
 */
async function rendersOneControlPerDescriptorInOrder(): Promise<void> {
  const log = createKeyLog();

  await renderWithProviders(
    <PfKeyBar
      keys={[
        binding('ENTER', TRANSACTION_DETAIL_KEY_LABELS.ENTER),
        binding('PFK03', TRANSACTION_DETAIL_KEY_LABELS.PFK03),
        binding('PFK05', TRANSACTION_DETAIL_KEY_LABELS.PFK05),
      ]}
      onInvoke={log.record}
    />,
  );

  expect(legendLabels()).toEqual([
    TRANSACTION_DETAIL_KEY_LABELS.ENTER,
    TRANSACTION_DETAIL_KEY_LABELS.PFK03,
    TRANSACTION_DETAIL_KEY_LABELS.PFK05,
  ]);
}

/**
 * Proves a one-key legend renders one key, with its bytes intact.
 *
 * ⚠️ Assumptions: `app/bms/COACTVW.bms:L373` paints `'  F3=Exit '` — two leading spaces and one
 * trailing space inside a `LENGTH=60` field — and that is the entire legend for that screen: ENTER
 * is deliberately not advertised even though `app/cbl/COACTVWC.cbl:L307-L308` admits it. The text
 * is compared against the element's `textContent` rather than against its accessible name, because
 * the accessible-name computation trims and collapses whitespace and would therefore accept a
 * screen that had normalised the padding away. `PfKeyBar.tsx` renders the label untrimmed for
 * exactly this reason, and HTML's own whitespace collapsing then displays it as the operator saw
 * it, so fidelity and appearance need no reconciling.
 * @returns {Promise<void>} Resolves once the single control has been asserted.
 */
async function rendersTheSingleKeyLegendVerbatim(): Promise<void> {
  const log = createKeyLog();

  await renderWithProviders(
    <PfKeyBar keys={[binding('PFK03', ACCOUNT_VIEW_KEY_LABELS.PFK03)]} onInvoke={log.record} />,
  );

  expect(legendLabels()).toEqual([ACCOUNT_VIEW_KEY_LABELS.PFK03]);
}

/**
 * Proves the uniform labels are the three whose wording never varies, and that they render.
 *
 * Assumptions: exactly three, and the omission of the other four is the decision worth pinning.
 * `PfKeyBar.tsx` records that `F4=Clear`, `F7=Backward` and `F8=Forward` are byte-identical
 * everywhere they appear across the seventeen measured legends, while ENTER is painted six
 * different ways, F3 three, F5 four and F12 two — so a default for any of those four would be
 * silently wrong on most screens rather than merely unhelpful, and wrong on a control that
 * performs a write in the F5 case. Asserting the set's exact membership is what stops a
 * well-meaning fourth entry being added later.
 * @returns {Promise<void>} Resolves once the three defaults have been rendered and asserted.
 */
async function appliesTheThreeUniformLabels(): Promise<void> {
  const log = createKeyLog();

  expect(Object.keys(UNIFORM_PF_KEY_LABELS)).toEqual(['PFK04', 'PFK07', 'PFK08']);

  await renderWithProviders(
    <PfKeyBar
      keys={[
        binding('PFK04', UNIFORM_PF_KEY_LABELS.PFK04),
        binding('PFK07', UNIFORM_PF_KEY_LABELS.PFK07),
        binding('PFK08', UNIFORM_PF_KEY_LABELS.PFK08),
      ]}
      onInvoke={log.record}
    />,
  );

  expect(legendLabels()).toEqual([
    UNIFORM_PF_KEY_LABELS.PFK04,
    UNIFORM_PF_KEY_LABELS.PFK07,
    UNIFORM_PF_KEY_LABELS.PFK08,
  ]);
}

/**
 * Pins the design-system emphasis mapping: primary for ENTER and PF5, default for the rest.
 *
 * ⚠️ Assumptions: which AIDs take primary emphasis is read from `PRIMARY_ACTION_AIDS` rather than
 * listed here, so the case checks the RENDERING against the module's own declaration instead of
 * against a second opinion about it. The migration plan's design-system section fixes the pairing
 * as `type="primary"` for ENTER and PF5 and `type="default"` for PF3, PF4 and PF12, and
 * `PfKeyBar.tsx` records that this is a design-system decision rather than an inference from the
 * baseline's own attributes — the 3270 expressed emphasis with `ATTRB=BRT`, which this tree
 * resolves to font weight, so terminal brightness and button emphasis are independent.
 *
 * Trade-offs: the assertion is made on the design system's own emphasis class, because the `type`
 * prop is reflected in no ARIA attribute and no `data-` attribute — the class is the only
 * observable the component publishes for it. That couples the case to one internal name of the
 * pinned antd 6.5.2, which is the cost accepted; against it, the alternative is to assert nothing
 * about emphasis at all, and the emphasis is what tells an operator which control writes.
 * @returns {Promise<void>} Resolves once every rendered control's emphasis has been asserted.
 */
async function givesPrimaryEmphasisToTheMappedAids(): Promise<void> {
  const log = createKeyLog();
  const descriptors: readonly PfKeyBinding[] = [
    binding('ENTER', MAIN_MENU_KEY_LABELS.ENTER),
    binding('PFK03', MAIN_MENU_KEY_LABELS.PFK03),
    binding('PFK04', UNIFORM_PF_KEY_LABELS.PFK04),
    binding('PFK05', USER_DELETE_KEY_LABELS.PFK05),
    binding('PFK12', USER_ADD_KEY_LABELS.PFK12),
  ];

  await renderWithProviders(<PfKeyBar keys={descriptors} onInvoke={log.record} />);

  expect(legendControls()).toHaveLength(descriptors.length);

  for (const descriptor of descriptors) {
    /*
     * WHY : Assumptions: each control is located by its accessible name rather than by position,
     *       so the assertion names the key it is about and cannot be satisfied by the wrong
     *       control if the order ever changed. Order is already asserted by the first case in this
     *       suite, so repeating it through an index here would test it twice and read as though
     *       emphasis depended on it.
     */
    expect(screen.getByRole('button', { name: descriptor.label })).toHaveClass(
      PRIMARY_ACTION_AIDS.includes(descriptor.aid) ? 'ant-btn-primary' : 'ant-btn-default',
    );
  }
}

/**
 * Proves each control's availability follows its own descriptor rather than the bar's state.
 *
 * Assumptions: an unavailable key is rendered DISABLED rather than removed, because that is what
 * the baseline does with the keys it validates: `app/cbl/COACTUPC.cbl:L905-L916` admits PF5 only
 * while the changes are validated but unconfirmed and PF12 only once the details have been
 * fetched, then reduces an invalid key to a screen refresh — the key stays present and inert. A
 * screen that instead needs a legend absent entirely omits the descriptor, which
 * `app/bms/COACTUP.bms:L498-L507` expresses by declaring its F5 and F12 fields `ATTRB=(ASKIP,DRK)`
 * and revealing them later. Both treatments are therefore expressible and neither is invented.
 * @returns {Promise<void>} Resolves once both controls have been asserted.
 */
async function disablesTheUnavailableControls(): Promise<void> {
  const log = createKeyLog();

  await renderWithProviders(
    <PfKeyBar
      keys={[
        binding('ENTER', MAIN_MENU_KEY_LABELS.ENTER, true),
        binding('PFK05', USER_DELETE_KEY_LABELS.PFK05, false),
      ]}
      onInvoke={log.record}
    />,
  );

  expect(screen.getByRole('button', { name: MAIN_MENU_KEY_LABELS.ENTER })).toBeEnabled();
  expect(screen.getByRole('button', { name: USER_DELETE_KEY_LABELS.PFK05 })).toBeDisabled();
}

/**
 * Pins the one mapset whose legend needs decoding before it can be displayed.
 *
 * ⚠️ Assumptions: BMS source is assembler macro source, in which `&` opens a variable symbol, so a
 * literal ampersand is written doubled. `app/bms/COUSR02.bms:L163` holds
 * `ENTER=Fetch  F3=Save&&Exit  F4=Clear  F5=Save  F12=Cancel` and the terminal displays ONE
 * ampersand — the doubling is source-level escaping rather than content, so carrying it across
 * would be a transcription defect that this tree's byte-exactness rule would then protect.
 *
 * Assumptions: the two forms are transcribed here rather than taken from the message catalog
 * because that catalog holds no entry for this screen's legend at all: it excludes the function-key
 * legends by name and assigns them to `PfKeyBar.tsx`, on the ground that a legend is a RENDERING of
 * the key bindings rather than their definition. So there is no catalog entry to compare against,
 * and the subject here is the decoder rather than the sentence.
 *
 * Assumptions: idempotence is asserted alongside, because `PfKeyBar.tsx` documents it and a screen
 * author may apply the decoder to an already-decoded label; a decoder that was not idempotent would
 * make a uniform "always decode" instruction unsafe.
 * @returns {Promise<void>} Resolves once the decoded label has been rendered and asserted.
 */
async function rendersAnEscapedAmpersandAsOneCharacter(): Promise<void> {
  const log = createKeyLog();
  const mapsetOperand = 'F3=Save&&Exit';
  const displayed = 'F3=Save&Exit';

  expect(decodeBmsLegendText(mapsetOperand)).toBe(displayed);
  expect(decodeBmsLegendText(displayed)).toBe(displayed);

  await renderWithProviders(
    <PfKeyBar
      keys={[binding('PFK03', decodeBmsLegendText(mapsetOperand))]}
      onInvoke={log.record}
    />,
  );

  expect(legendLabels()).toEqual([displayed]);
}

/**
 * Proves a bound-but-unpainted key contributes no control while remaining dispatchable.
 *
 * ⚠️ Assumptions: this is a real baseline shape rather than a degenerate input.
 * `app/cbl/COUSR03C.cbl:L123-L125` binds `DFHPF12` while the row-24 literal of
 * `app/bms/COUSR03.bms` advertises only four actions, so the key works and is not advertised.
 * `usePfKeys.ts` represents that as a binding with an empty label and `PfKeyBar.tsx` renders no
 * control for one, because an unnamed button is a control the terminal never showed and one with no
 * accessible name.
 *
 * Assumptions: the key press is asserted as well as the absence, since the absence alone would also
 * be satisfied by a binding that had been dropped entirely — and dropping it would silently remove
 * a working key.
 * @returns {Promise<void>} Resolves once the absence and the dispatch have both been asserted.
 */
async function paintsNoControlForAKeyboardOnlyBinding(): Promise<void> {
  const log = createKeyLog();
  const { user } = await renderWithProviders(
    <KeyProbe handlers={keyboardOnlyHandlers(['PFK12'], log)} reportsInvalidKey />,
  );

  /*
   * WHY : Assumptions: the landmark is absent ENTIRELY rather than present and empty, because
   *       `PfKeyBar.tsx` returns nothing when no descriptor is renderable — a named landmark
   *       holding no control would be an entry in a screen reader's landmark list that leads
   *       nowhere. That is why the query is for the landmark and not for its buttons.
   */
  expect(screen.queryByRole('navigation', { name: PF_KEY_BAR_REGION_LABEL })).toBeNull();

  await pressBrowserKey(user, functionKeyName(12));

  expect(log.received).toEqual(['PFK12']);
}

/**
 * Registers the cases covering the per-screen descriptor array.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function descriptorArrayCases(): void {
  it(
    'renders one control per descriptor, in the order supplied',
    rendersOneControlPerDescriptorInOrder,
  );
  it(
    'renders the single-key legend of the account-view mapset verbatim',
    rendersTheSingleKeyLegendVerbatim,
  );
  it(
    'applies the three uniform labels when a screen supplies none of its own',
    appliesTheThreeUniformLabels,
  );
  it(
    'gives primary emphasis to exactly the AIDs the design-system mapping names',
    givesPrimaryEmphasisToTheMappedAids,
  );
  it(
    'disables the controls whose descriptors report themselves unavailable',
    disablesTheUnavailableControls,
  );
  it(
    'renders a BMS-escaped ampersand as the single character the terminal displayed',
    rendersAnEscapedAmpersandAsOneCharacter,
  );
  it(
    'paints no control for a keyboard-only binding but still dispatches its key',
    paintsNoControlForAKeyboardOnlyBinding,
  );
}

describe('the per-screen descriptor array', descriptorArrayCases);

/**
 * A notional screen that publishes each binding's resolved semantic action for inspection.
 *
 * Alternatives Considered: asserting on `usePfKeys`' return value by calling the hook from a bare
 * test function. Rejected because a hook called outside a component throws, and the two library
 * routes around that — a renderer harness, or a hook-testing wrapper — would each observe the hook
 * in a tree the application never builds. Rendering the actions is one line and keeps the subject
 * the mounted hook.
 * @param {object} props - The probe's inputs.
 * @param {PfKeyHandlerMap} props.handlers - The handler map the notional screen registers.
 * @returns {ReactElement} One element per binding, carrying that binding's action as its text.
 */
function ActionProbe({ handlers }: { readonly handlers: PfKeyHandlerMap }): ReactElement {
  const { bindings } = usePfKeys(handlers);

  return <ul>{bindings.map(renderBindingAction)}</ul>;
}

/**
 * Pins the seven default pairings against the module's own table.
 *
 * Assumptions: the expectation is written out in {@link EXPECTED_DEFAULT_ACTIONS} rather than
 * derived, which is what makes this case worth having — a derived expectation would agree with any
 * table. The seven are the measured semantics of the baseline, and `app/cbl/COUSR02C.cbl:L110-L127`
 * exhibits four of them in a single `EVALUATE`: PF3 updates and returns, PF4 clears the screen, PF5
 * updates in place and PF12 returns to the administrative menu.
 *
 * Assumptions: the table is asserted to hold SEVEN entries and no more, because the eight members
 * of `PfKeyAction` include `screen-defined` precisely so that a key with no shared meaning has
 * somewhere to go. An eighth default would take that key's meaning away from the screen that owns
 * it — `app/app-transaction-type-db2/cbl/COTRTLIC.cbl` binds PF2 and PF10, which the shared shell
 * gives no meaning at all.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function givesExactlySevenKeysASharedMeaning(): void {
  expect(DEFAULT_PF_KEY_ACTIONS).toEqual(EXPECTED_DEFAULT_ACTIONS);
  expect(Object.keys(DEFAULT_PF_KEY_ACTIONS)).toHaveLength(7);
}

/**
 * Proves each binding carries the semantic action its AID resolves to.
 *
 * Assumptions: the actions are read off the rendered bindings rather than off the table, so this
 * checks that `usePfKeys` APPLIES its own defaults rather than merely declaring them. A screen
 * supplies no `action` here, which is the ordinary case: only a screen whose key means something
 * else overrides one.
 * @returns {Promise<void>} Resolves once every binding's action has been asserted.
 */
async function resolvesEachKeyToItsDefaultAction(): Promise<void> {
  const log = createKeyLog();
  /*
   * WHY : Assumptions: the seven AIDs are gathered by walking the DOMAIN and keeping those the
   *       shared table names, rather than by reading the table's own keys. `Object.keys` widens a
   *       key to `string`, so that route would need either a cast back to `CicsAid` — an assertion
   *       the compiler cannot check — or a narrowing predicate written to compensate for it.
   *       Walking the domain keeps every value genuinely typed and, as a by-product, yields them in
   *       the copybook's own order.
   */
  const roleAids = CICS_AIDS.filter(hasADefaultAction);

  await renderWithProviders(<ActionProbe handlers={keyboardOnlyHandlers(roleAids, log)} />);

  for (const aid of roleAids) {
    expect(screen.getByTestId(`action-${aid}`)).toHaveTextContent(
      String(EXPECTED_DEFAULT_ACTIONS[aid]),
    );
  }
}

/**
 * Proves each of the seven keys dispatches its own handler and no other.
 *
 * Assumptions: the six function keys are pressed before ENTER, and ENTER only after the entry
 * field is focused, for the reason the ENTER case in the previous suite establishes — the shell
 * defers ENTER to a control that would act on it, so pressing it while focus sat on a legend
 * control would activate that control instead. The probe here registers keyboard-only handlers, so
 * it paints no control at all and the ordering is a safeguard rather than a necessity; it is kept
 * because the reason is easy to lose and expensive to rediscover.
 * @returns {Promise<void>} Resolves once all seven keys have been pressed and the log asserted.
 */
async function dispatchesEachOfTheSevenKeys(): Promise<void> {
  const log = createKeyLog();
  const { user } = await renderReportingScreen(
    keyboardOnlyHandlers(['ENTER', 'PFK03', 'PFK04', 'PFK05', 'PFK07', 'PFK08', 'PFK12'], log),
  );

  for (const ordinal of [3, 4, 5, 7, 8, 12]) {
    await pressBrowserKey(user, functionKeyName(ordinal));
  }

  await user.click(screen.getByLabelText('Entry'));
  await pressBrowserKey(user, 'Enter');

  expect(log.received).toEqual(['PFK03', 'PFK04', 'PFK05', 'PFK07', 'PFK08', 'PFK12', 'ENTER']);
}

/**
 * Proves a screen may give a key its own meaning, which is how one measured exception is expressed.
 *
 * ⚠️ Assumptions: PF8 does NOT mean page-forward everywhere, and the exception is measured rather
 * than hypothetical. `app/app-authorization-ims-db2-mq/bms/COPAU01.bms:L292` paints
 * `' F3=Back  F5=Mark/Remove Fraud  F8=Next Auth'` — so on the authorization DETAIL screen PF8
 * steps to the next authorization record, not to the next page of a list. That screen's own test
 * file asserts that semantic; what is asserted here is the MECHANISM that lets it, namely that an
 * explicit `action` on the handler entry overrides the shared default. Without this, expressing the
 * exception would require either a wrong shared default or a fabricated one.
 *
 * Assumptions: `screen-defined` is the action used, because it is the member `PfKeyAction` carries
 * for exactly this purpose and because naming the authorization screen's own verb here would put a
 * second screen's vocabulary into a shared contract.
 * @returns {Promise<void>} Resolves once the overridden action has been asserted.
 */
async function letsAScreenOverrideADefaultAction(): Promise<void> {
  const log = createKeyLog();

  await renderWithProviders(
    <ActionProbe
      handlers={{
        PFK08: { onInvoke: log.record, action: 'screen-defined' },
      }}
    />,
  );

  expect(screen.getByTestId('action-PFK08')).toHaveTextContent('screen-defined');
  expect(DEFAULT_PF_KEY_ACTIONS.PFK08).toBe('page-forward');
}

/**
 * Records the one legend in the population that advertises a key its program never dispatches.
 *
 * ⚠️ Assumptions: this is a legend-versus-behaviour mismatch in the baseline, and it is recorded
 * rather than reconciled. `app/app-transaction-type-db2/bms/COTRTUP.bms:L130` paints `'F6=Add'`
 * and `app/app-transaction-type-db2/cpy-bms/COTRTUP.cpy:L102` carries a dedicated
 * `FKEY06I PIC X(6)` field for it — yet the program's own attention-identifier handling covers only
 * `CCARD-AID-ENTER`, `PFK03`, `PFK04`, `PFK05` and `PFK12`, verified by reading every
 * `CCARD-AID-*` reference in `COTRTUPC.cbl`. So the key is painted and not bound.
 *
 * Assumptions: no F6 handler is fabricated anywhere in this tree, and the assertion that pins that
 * is the absence of a shared default for `PFK06`. `usePfKeys` resolves an unlisted AID to
 * `screen-defined`, so the migrated `refTypeEdit` screen can paint the legend byte for byte while
 * binding nothing to it — which is what fidelity to the baseline requires here, since inventing the
 * add action would be new behaviour rather than migrated behaviour.
 * @returns {void} Nothing; the assertions either pass or fail the case.
 */
function givesPf6NoSharedMeaning(): void {
  expect(DEFAULT_PF_KEY_ACTIONS.PFK06).toBeUndefined();
  expect(CICS_AIDS).toContain('PFK06');
}

/**
 * Registers the cases covering the semantic roles the shared shell fixes.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function semanticRoleCases(): void {
  it(
    'gives exactly seven attention identifiers a shared meaning',
    givesExactlySevenKeysASharedMeaning,
  );
  it('resolves each registered key to its default action', resolvesEachKeyToItsDefaultAction);
  it('dispatches each of the seven keys to its own handler', dispatchesEachOfTheSevenKeys);
  it(
    'lets a screen override a default action for a key that means something else',
    letsAScreenOverrideADefaultAction,
  );
  it(
    'gives PF6 no shared meaning, so a painted F6 legend can stay unbound',
    givesPf6NoSharedMeaning,
  );
}

describe('the semantic roles the shared shell fixes', semanticRoleCases);

/**
 * Proves forward and backward availability follow the envelope and the client-held ordinal.
 *
 * ⚠️ Assumptions: PF8's availability comes from the envelope's `hasNext` and PF7's from the
 * client-derived `hasPrev`, never from a page number compared against a total — `PageResponse`
 * declares exactly four members and carries no total to compare against. On the opening page PF7 is
 * unavailable because there is no earlier page, exactly as `app/cbl/COCRDLIC.cbl:L901-L903` refuses
 * the backward step from `CA-FIRST-PAGE` alone with no backward read issued. One forward step then
 * reaches the terminal page, where PF8 becomes unavailable and PF7 becomes available — so the two
 * flags are observed in both of their combinations from one mounted browse.
 *
 * Assumptions: the ordinal is asserted alongside because it is the value `hasPrev` is derived from,
 * and a case that asserted only the two flags could not distinguish a browse that had genuinely
 * stepped from one that had merely toggled them.
 * @returns {Promise<void>} Resolves once both pages have been observed.
 */
async function followsTheEnvelopeAcrossAForwardStep(): Promise<void> {
  const { user } = await renderWithProviders(<BrowseProbe hasNextOnOpeningPage />);

  await screen.findByText(OPENING_PAGE_ROWS[0] ?? '');

  const openingForward = screen.getByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK08 });

  expect(openingForward).toBeEnabled();
  expect(screen.getByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK07 })).toBeDisabled();
  expect(screen.getByTestId('page-ordinal')).toHaveTextContent('1');

  await user.click(openingForward);
  await screen.findByText(FURTHER_PAGE_ROWS[0] ?? '');

  expect(screen.getByTestId('page-ordinal')).toHaveTextContent('2');
  expect(screen.getByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK07 })).toBeEnabled();
  expect(screen.getByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK08 })).toBeDisabled();
}

/**
 * Proves a single-page browse leaves both paging keys unavailable.
 *
 * Assumptions: this is the case an offset-based implementation would most easily get wrong, because
 * a page that is both first and last has no boundary to compute from — there is no row count and no
 * total to compare against. The envelope answers it directly: `hasNext` is false as delivered, and
 * the ordinal is the opening one, so both keys are withheld and neither is reported as an error.
 * @returns {Promise<void>} Resolves once both controls have been asserted.
 */
async function withholdsBothKeysOnASinglePageBrowse(): Promise<void> {
  await renderWithProviders(<BrowseProbe hasNextOnOpeningPage={false} />);

  await screen.findByText(OPENING_PAGE_ROWS[0] ?? '');

  expect(screen.getByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK07 })).toBeDisabled();
  expect(screen.getByRole('button', { name: UNIFORM_PF_KEY_LABELS.PFK08 })).toBeDisabled();
}

/**
 * Registers the cases covering the paging keys and the keyset envelope.
 * @returns {void} Nothing; the cases are registered with the runner.
 */
function pagingKeyCases(): void {
  it(
    'enables PF8 while the envelope reports a further page and PF7 once a step has been taken',
    followsTheEnvelopeAcrossAForwardStep,
  );
  it(
    'withholds both paging keys on a browse that fits one page',
    withholdsBothKeysOnASinglePageBrowse,
  );
}

describe('the paging keys and the keyset envelope', pagingKeyCases);
