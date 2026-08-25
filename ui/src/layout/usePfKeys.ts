/**
 * @file Centralizes CICS attention-identifier (AID) keyboard semantics for the React
 * layout layer.
 *
 * The mappings port `YYYY-STORE-PFKEY` from `app/cpy/CSSTRPFY.cpy:L21-L78`,
 * while the domain mirrors `app/cpy/CVCRD01Y.cpy:L3-L19`. Screen components
 * supply their own labels and handlers; this module only owns normalization,
 * availability, and dispatch.
 *
 * Assumptions: This module belongs in `layout/` because AAP §0.4.1.4 defines
 * PF-key behavior as a shell contract shared by keyboard and `PfKeyBar`, rather
 * than as remote-data state owned by the hooks layer.
 */

import { useCallback, useEffect, useRef } from 'react';
import type { RefObject } from 'react';

import { INVALID_KEY_PRESSED } from '../messages/messages';

// Alternatives Considered: Importing `usePagedQuery` to coordinate page keys
// would invert its declared dependency on layout vocabulary and create a cycle;
// handler predicates keep live screen state at the consuming-screen boundary.

/**
 * Ordered CICS AID values exposed by `CCARD-AID` in
 * `app/cpy/CVCRD01Y.cpy:L3-L19`.
 *
 * Assumptions: PA1 and PA2 omit the copybook's trailing spaces because those
 * spaces only pad a `PIC X(5)` field. All twelve PF values remain in the domain
 * because `COTRTLIC.cbl:L574-L587` uses PF2 and conditionally uses PF10 even
 * though the shared shell most often exposes seven keys.
 */
export const CICS_AIDS = Object.freeze([
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
] as const);

/**
 * Canonical CICS AID accepted by PF-key handlers.
 */
export type CicsAid = (typeof CICS_AIDS)[number];

/**
 * Browser function-key aliases corresponding to the PF13-PF24 branches in
 * `app/cpy/CSSTRPFY.cpy:L54-L77`.
 *
 * Assumptions: PF13-PF24 intentionally collapse onto PF1-PF12 rather than
 * forming additional AIDs. This preserves the copybook contract and also means
 * a shifted function key remains observationally equivalent to its base PF key.
 */
export const PF_KEY_ALIASES: Readonly<Record<string, CicsAid>> = Object.freeze({
  F13: 'PFK01',
  F14: 'PFK02',
  F15: 'PFK03',
  F16: 'PFK04',
  F17: 'PFK05',
  F18: 'PFK06',
  F19: 'PFK07',
  F20: 'PFK08',
  F21: 'PFK09',
  F22: 'PFK10',
  F23: 'PFK11',
  F24: 'PFK12',
});

/**
 * Browser `KeyboardEvent.key` values that normalize to CICS AIDs.
 *
 * Alternatives Considered: CLEAR, PA1, and PA2 remain in `CicsAid` but have no
 * invented browser shortcuts. The copybook normalizes those terminal AIDs,
 * while measured usage across all 21 online programs is zero, so assigning
 * arbitrary web keys would create behavior absent from the source application.
 */
export const KEYBOARD_KEY_TO_AID: Readonly<Record<string, CicsAid>> = Object.freeze({
  Enter: 'ENTER',
  F1: 'PFK01',
  F2: 'PFK02',
  F3: 'PFK03',
  F4: 'PFK04',
  F5: 'PFK05',
  F6: 'PFK06',
  F7: 'PFK07',
  F8: 'PFK08',
  F9: 'PFK09',
  F10: 'PFK10',
  F11: 'PFK11',
  F12: 'PFK12',
  ...PF_KEY_ALIASES,
});

/**
 * Semantic action represented by a registered PF-key handler.
 */
export type PfKeyAction =
  | 'submit'
  | 'back'
  | 'clear'
  | 'save'
  | 'page-backward'
  | 'page-forward'
  | 'cancel'
  | 'screen-defined';

/**
 * Default semantic actions observed across the online COBOL programs.
 *
 * Keys omitted from this partial map resolve to `screen-defined`, allowing
 * screens such as transaction-type maintenance to bind PF2 and PF10 without
 * assigning misleading global semantics.
 */
export const DEFAULT_PF_KEY_ACTIONS: Readonly<Partial<Record<CicsAid, PfKeyAction>>> =
  Object.freeze({
    ENTER: 'submit',
    PFK03: 'back',
    PFK04: 'clear',
    PFK05: 'save',
    PFK07: 'page-backward',
    PFK08: 'page-forward',
    PFK12: 'cancel',
  });

/**
 * How much a registered PF key's action RISKS, which is what its emphasis must report.
 *
 * The three members are ordered by consequence and are mutually exclusive:
 *
 * - `'read-only'` — the action reads or navigates and writes nothing. `ENTER=Fetch` on
 *   `app/bms/COUSR03.bms:L148`, `F5=Browse Tran.` on `app/bms/COTRN01.bms:L267`, every paging key,
 *   `F3=Exit`, `F4=Clear` and `F12=Cancel`. Pressing it twice by accident costs a round trip.
 * - `'mutating'` — the action writes a record the operator can come back and edit. `F5=Save` on
 *   `app/bms/COACTUP.bms`, `ENTER=Add User` on `app/bms/COUSR01.bms:L159`, and `F3=Save&&Exit` on
 *   `app/bms/COUSR02.bms:L163`. Pressing it twice writes twice.
 * - `'destructive'` — the action destroys a record, so it cannot be undone from the screen that
 *   offered it. `F5=Delete` on `app/bms/COUSR03.bms:L148`, `F4=Delete` on the transaction-type
 *   extension's edit map, and marking an authorization as fraud.
 *
 * ⚠️ Refactoring Rationale: a bill payment was listed under `'destructive'` here and is not one.
 * `app/cbl/COBIL00C.cbl` L173-L191 writes a transaction record and reduces the account balance,
 * which is a record the operator can come back to - the transaction is listed on
 * `app/bms/COTRN00.bms` and viewable on `app/bms/COTRN01.bms` - so it is `'mutating'`, and that is
 * what the screen declares. Assumptions: the wording "or moves money" is withdrawn with it, because
 * moving money is not the test. The test is whether the record survives the action: a payment adds
 * one, a delete removes one. Leaving the entry would have told the next screen to paint its paying
 * Enter as a danger control, which overstates a posting the ledger keeps a full record of and
 * devalues the emphasis on the deletes that genuinely cannot be walked back.
 *
 * Assumptions: the distinction is what the action DOES to stored state, never how alarming its
 * label reads and never which key carries it. That is the whole point of the member existing:
 * `PFK05` is `F5=Delete` on `app/bms/COUSR03.bms:L148` and `F5=Save` on `app/bms/COACTUP.bms`, and
 * `PFK03` is `F3=Back` on nine mapsets and `F3=Save&&Exit` on `app/bms/COUSR02.bms:L163`, so no
 * mapping from AID to risk can be right on more than a subset of screens. A screen states its own.
 *
 * Assumptions: `'mutating'` and `'destructive'` are two members rather than one boolean because
 * `ui/src/layout/PfKeyBar.tsx` resolves them to two DIFFERENT emphases — a save must read as the
 * screen's primary action while a delete must read as the dangerous one — and a boolean could only
 * express one of those two distinctions. Collapsing them would put a delete and a save in the same
 * paint, which is the defect the member was added to fix.
 *
 * Trade-offs: the member is OPTIONAL wherever it appears, so a screen that states nothing keeps the
 * emphasis it has today. Making it required would have been the stronger contract and would have
 * forced every one of the 21 screens to answer at once; the cost accepted is that an un-stated key
 * falls back to the AID-keyed default, which is documented at
 * `ui/src/layout/PfKeyBar.tsx`'s `PRIMARY_ACTION_AIDS` and is right for the majority case and
 * wrong for exactly the minority this union exists to describe.
 */
export type PfKeyRisk = 'read-only' | 'mutating' | 'destructive';

/**
 * Reason that a recognized CICS AID could not be dispatched and WAS reported.
 *
 * Assumptions: the union stays at two members even though {@link usePfKeys}' dispatch chain now
 * declines an AID three ways. A key whose own turn is still in flight — see
 * {@link PfKeyHandlerEntry.busy} — is declined silently and never appears here, because it is a
 * VALID key that arrived early: reporting the baseline's invalid-key text for it would tell the
 * operator the key does not work, which is a different and false statement. Adding a third member
 * would also route that case into `onInvalidKey`, and every screen wires that to its error band.
 */
export type PfKeyRejectionReason = 'unmapped' | 'disabled';

/**
 * Error-channel payload emitted for a recognized but unavailable AID.
 */
export interface PfKeyRejection {
  /** Canonical AID that the browser key normalized to. */
  readonly aid: CicsAid;
  /** Byte-preserved invalid-key text imported from the message catalog. */
  readonly message: typeof INVALID_KEY_PRESSED;
  /** Message severity expected by screen-level status renderers. */
  readonly severity: 'error';
  /** Whether the screen omitted the handler or disabled it for current state. */
  readonly reason: PfKeyRejectionReason;
}

/**
 * Screen-owned behavior and presentation metadata for one CICS AID.
 */
export interface PfKeyHandlerEntry {
  /**
   * Executes the screen action represented by the normalized AID.
   *
   * @param {CicsAid} aid - Canonical AID being invoked.
   * @returns {void} The handler completes through its screen-owned side effects.
   * @throws {unknown} Screen-owned failures propagate to the application error
   * boundary.
   */
  readonly onInvoke: (aid: CicsAid) => void;
  /** Optional semantic override for screen-specific behavior. */
  readonly action?: PfKeyAction;
  /** Optional screen-specific legend text rendered by `PfKeyBar`. */
  readonly label?: string;
  /**
   * Evaluates whether the handler is unavailable in current screen state.
   *
   * @returns {boolean} `true` when the AID must be rejected as disabled.
   * @throws {unknown} Screen-owned predicate failures propagate to the
   * application error boundary.
   */
  readonly disabled?: boolean | (() => boolean);
  /**
   * What this key's action risks, which fixes the emphasis `PfKeyBar` paints it with.
   *
   * Assumptions: omitting it is a statement that the screen has not classified the key, not a
   * statement that the key is safe. `PfKeyBar` then falls back to its AID-keyed default, so a
   * screen that says nothing renders exactly as it does today. See {@link PfKeyRisk} for the three
   * members and for why no default can be derived from the AID.
   */
  readonly risk?: PfKeyRisk;
  /**
   * Evaluates whether THIS key's own turn is still in flight.
   *
   * Purpose: give a screen a way to say "the turn you started with this key has not come back
   * yet". A measured double-submit found the pressed control left indistinguishable from idle on
   * three read actions — two clicks 400 ms apart produced two identical requests and a list that
   * displayed page 2 while reporting page 3 — because the only busy affordance in the frame was the
   * table's own spinner, which is nowhere near the control that was pressed.
   *
   * Assumptions: the flag is PER KEY and not per screen, so a long-running read on one key leaves
   * every other key live. That matches the reference, where a transaction in flight inhibited the
   * whole terminal but the operator's next attention key was queued against the SAME turn, and it
   * matters here because a screen must stay escapable: `F3=Exit` has to work while a fetch is
   * outstanding.
   *
   * Assumptions: the predicate form exists for the same reason `disabled`'s does — a screen holding
   * its in-flight state in a ref rather than in state can answer from the ref, which is the pattern
   * three screens in this tree already use to make their guard effective on the same task as the
   * click rather than one render later.
   *
   * Trade-offs: a screen that sets this and never clears it leaves the key permanently announced as
   * busy and, per {@link usePfKeys}, permanently undispatchable. That is the same hazard `disabled`
   * already carries, and it is accepted for the same reason: the alternative is a timeout in this
   * module, which would have to guess a duration the screen knows and this module does not.
   * @returns {boolean} `true` while this key's turn is outstanding.
   * @throws {unknown} Screen-owned predicate failures propagate to the application error boundary.
   */
  readonly busy?: boolean | (() => boolean);
}

/**
 * Sparse set of AID handlers registered by the active screen.
 */
export type PfKeyHandlerMap = Partial<Readonly<Record<CicsAid, PfKeyHandlerEntry>>>;

/**
 * Render-ready PF-key metadata shared with `PfKeyBar`.
 */
export interface PfKeyBinding {
  /** Canonical AID represented by the binding. */
  readonly aid: CicsAid;
  /** Semantic action used for presentation and analytics. */
  readonly action: PfKeyAction;
  /** Screen-owned legend text, or an empty string for keyboard-only handlers. */
  readonly label: string;
  /** Whether the binding can be invoked in the current render. */
  readonly enabled: boolean;
  /**
   * What the key's action risks, when the screen classified it; absent when it did not.
   *
   * Assumptions: absence is carried through as an ABSENT MEMBER rather than as a default value,
   * because the renderer has to be able to tell "this screen says the key is read-only" from "this
   * screen has not said", and those two resolve to different emphases —
   * `ui/src/layout/PfKeyBar.tsx` de-emphasises the first and applies its AID-keyed fallback to the
   * second. Substituting a default here would erase that distinction before the renderer sees it.
   */
  readonly risk?: PfKeyRisk;
  /**
   * Whether this key's own turn is in flight, when the screen reports it; absent when it does not.
   *
   * Assumptions: absence is again an absent member rather than `false`, and the difference is
   * visible. `ui/src/layout/PfKeyBar.tsx` reserves the busy affordance's own box on a control that
   * participates in this channel — including while the value is `false` — so that the affordance
   * appearing cannot change the control's width; a control that never participates reserves nothing
   * and renders exactly as it does today. So `false` means "not busy now, and this key can become
   * busy", and absence means "this key never reports busy".
   */
  readonly busy?: boolean;
}

/**
 * Configuration for keyboard-listener scope and invalid-key reporting.
 */
export interface UsePfKeysOptions {
  /** Disables all dispatch without unregistering the screen's handler map. */
  readonly enabled?: boolean;
  /**
   * Element the keydown listener is installed on; `document` when absent or
   * `null`.
   *
   * Refactoring Rationale: this is the target ITSELF and not a ref to it, because
   * the effect that installs the listener has to be able to react to a change of
   * target and a ref object never changes. Passing a ref made the dependency the
   * container rather than the element, so a screen that re-pointed a stable ref at
   * a different node - a panel that mounts conditionally, for instance - kept the
   * listener on the node it had already unmounted, with no cleanup and no
   * re-registration. Taking the element makes the dependency the thing that
   * actually varies.
   *
   * Assumptions: a caller that needs to scope the listener to a mounted element
   * holds that element in state and sets it from a callback ref, which is what
   * makes the element available as a render value at all; reading `ref.current`
   * during render would reintroduce the same staleness one level up.
   */
  readonly target?: EventTarget | null | undefined;
  /**
   * Receives recognized AIDs that are unmapped or currently disabled.
   *
   * @param {PfKeyRejection} rejection - Invalid-key payload for the screen's
   * message channel.
   * @returns {void} Completion is represented by screen-owned message state.
   * @throws {unknown} Screen-owned reporting failures propagate to the
   * application error boundary.
   */
  readonly onInvalidKey?: (rejection: PfKeyRejection) => void;
  /**
   * Optional focus destination matching the COBOL `MOVE -1 TO <field>L`
   * invalid-key affordance.
   */
  readonly restoreFocusRef?: RefObject<HTMLElement | null>;
}

/**
 * PF-key bindings and the stable imperative dispatch path returned by the hook.
 */
export interface UsePfKeysResult {
  /** Registered handlers transformed into ordered render metadata. */
  readonly bindings: readonly PfKeyBinding[];
  /**
   * Invokes one canonical AID through the same validation path as keydown.
   *
   * @param {CicsAid} aid - Canonical AID to dispatch.
   * @returns {boolean} `true` when an enabled handler ran; otherwise `false`.
   * @throws {unknown} Screen callback, predicate, reporting, or focus failures
   * propagate to the application error boundary.
   */
  readonly invoke: (aid: CicsAid) => boolean;
}

/**
 * Latest screen-owned values read by the stable listener and invoke callback.
 */
interface CurrentPfKeyState {
  /** Handler map from the most recent render. */
  readonly handlers: PfKeyHandlerMap;
  /** Hook options from the most recent render. */
  readonly options: UsePfKeysOptions;
}

/**
 * Removes the concrete DOM listener installed by one effect execution.
 */
type PfKeyListenerCleanup = () => void;

/**
 * Normalizes a browser key name using the port of `YYYY-STORE-PFKEY`.
 *
 * Assumptions: the lookup is restricted to the table's OWN properties, and the
 * restriction is load-bearing rather than defensive style. `KEYBOARD_KEY_TO_AID`
 * is a frozen plain object, so it still inherits from `Object.prototype`, and a
 * bare index would resolve `"constructor"`, `"toString"` and `"valueOf"` to
 * functions and `"__proto__"` to an object. This function is exported and accepts
 * an arbitrary string, so without the guard its declared return type would be a
 * claim the implementation does not keep — a caller trusting it could hand a
 * function to `invoke` and reach a state no AID represents. No real
 * `KeyboardEvent.key` value spells any of those names, so this closes a contract
 * hole rather than an observed defect, and closing it now costs one call.
 *
 * Alternatives Considered: building the table with a null prototype, or as a
 * `Map`. Both remove the hazard at the source and both were rejected on the
 * table's other obligations: it is a documented, frozen, exported constant that
 * `ui/src/layout/PfKeyBar.tsx` inverts with `Object.entries` to derive the
 * key each control advertises, and a `Map` would change that consumer's shape
 * while a null-prototype object cannot be written as a typed object literal
 * without a cast. Guarding the one read keeps the published shape and the
 * inversion untouched.
 *
 * @param {string} key - Browser `KeyboardEvent.key` value to normalize.
 * @returns {CicsAid | undefined} Matching CICS AID, or `undefined` when the
 * key is not a terminal attention identifier represented on the web.
 */
export function resolveAid(key: string): CicsAid | undefined {
  return Object.hasOwn(KEYBOARD_KEY_TO_AID, key) ? KEYBOARD_KEY_TO_AID[key] : undefined;
}

/**
 * Normalizes a real keyboard event while enforcing the modifier policy.
 *
 * Assumptions: Shift remains allowed because a shifted terminal function key
 * represented PF13-PF24, which `CSSTRPFY.cpy:L54-L77` aliases back to the same
 * PF1-PF12 AID. Control, Alt, and Meta combinations remain browser or
 * operating-system commands and therefore are not claimed by the application.
 *
 * @param {KeyboardEvent} event - DOM keyboard event to inspect.
 * @returns {CicsAid | undefined} Matching CICS AID, or `undefined` when the
 * event is modified or does not represent a mapped attention identifier.
 */
export function resolveAidFromKeyboardEvent(event: KeyboardEvent): CicsAid | undefined {
  if (event.altKey || event.ctrlKey || event.metaKey) {
    return undefined;
  }

  return resolveAid(event.key);
}

/*
 * Refactoring Rationale: this policy exists because the listener is installed on
 * the document by default, which is what the 3270 contract requires - a terminal
 * delivered an attention identifier to the transaction regardless of which field
 * the cursor sat in - but a browser has one behaviour the terminal did not: some
 * elements act on Enter themselves. Without a policy the shell cancelled that
 * activation and dispatched the screen's own ENTER verb instead, so pressing
 * Enter on a focused function-key button ran the screen's submit rather than the
 * button. The policy is deliberately narrow: it defers ONLY the ENTER
 * identifier, because ENTER is the only member of `KEYBOARD_KEY_TO_AID` that any
 * element consumes natively, and a function key must keep working wherever focus
 * is - `COACTUPC.cbl:L905-L916` validates PF5 and PF12 against screen state, not
 * against cursor position.
 */

/**
 * Selector matching the elements a browser acts on when Enter is pressed with no
 * modifier.
 *
 * Assumptions: the list is the set of NATIVE activations, and it is limited to
 * those on purpose. A widget that handles Enter in JavaScript - a combobox
 * committing a highlighted option, for instance - marks the event as handled by
 * calling `preventDefault` on it, and the keydown listener bails on an
 * already-handled event before this selector is consulted, so such widgets need
 * no entry here. What the selector covers is the case that guard cannot see:
 * activation the browser performs AFTER every keydown listener has run, which is
 * silently lost if the shell cancels the event first.
 *
 * Assumptions: the two ARIA roles are included even though a browser activates
 * neither of them natively. They name elements that have taken a control's
 * semantics without its implementation, so a screen reader tells the user Enter
 * will activate them; honouring that promise costs nothing and breaking it is
 * indistinguishable from a defect.
 *
 * Assumptions: a text input, a checkbox and a radio are deliberately ABSENT.
 * Enter in a text field is exactly the 3270 submit gesture the shell must claim,
 * and Enter on a checkbox or radio does not toggle it in any browser - the space
 * key does - so those three keep the shell's dispatch, which is the behaviour
 * being migrated rather than an omission.
 */
export const ENTER_ACTIVATED_TARGET_SELECTOR = [
  'button',
  '[role="button"]',
  'a[href]',
  'area[href]',
  '[role="link"]',
  'summary',
  'select',
  'textarea',
  'input[type="button"]',
  'input[type="image"]',
  'input[type="reset"]',
  'input[type="submit"]',
  '[contenteditable=""]',
  '[contenteditable="true"]',
].join(', ');

/**
 * Reports whether the event's own target will act on the key, so the shell must
 * leave it alone.
 *
 * Assumptions: the match is resolved with `closest` rather than by testing the
 * target itself, because a control's own descendant is frequently what receives
 * the event - a design-system button wraps its label in an inner element - and
 * testing only the target would defer for a bare button while claiming the same
 * key from a labelled one.
 *
 * Assumptions: this requires a DOM, and the guard against a non-element target
 * covers the document itself, which is the default listener target and matches
 * nothing.
 *
 * @param {CicsAid} aid - Canonical AID the browser key normalized to.
 * @param {EventTarget | null} target - The keydown event's target.
 * @returns {boolean} `true` when dispatch must be skipped so the target's own
 * behaviour survives.
 */
export function isAidClaimedByEventTarget(aid: CicsAid, target: EventTarget | null): boolean {
  if (aid !== 'ENTER' || !(target instanceof Element)) {
    return false;
  }

  return target.closest(ENTER_ACTIVATED_TARGET_SELECTOR) !== null;
}

/**
 * Reports whether a keydown carries no dispatchable attention identifier because
 * of the event's own state.
 *
 * Assumptions: auto-repeat is suppressed because a 3270 delivered exactly one
 * attention identifier per attention key. Holding a key down in a browser
 * produces a stream of keydown events, so without this an operator resting on F5
 * would run a save as many times as the platform's repeat rate allows - and PF5
 * is a write verb on `app/bms/COACTUP.bms` and, in the transaction-type
 * extension tree, `app/app-transaction-type-db2/bms/COTRTUP.bms`.
 *
 * Assumptions: an in-composition event is suppressed because a user committing
 * an input-method composition presses Enter to accept candidate text, not to
 * submit the screen. The browser reports that state on the event, so honouring it
 * needs no knowledge of which input method is in use.
 *
 * Assumptions: an already-handled event is suppressed because something nearer
 * the target has claimed the key - a component's own key handler, or another
 * listener. Dispatching anyway would run two actions for one keystroke, and the
 * shell is the outer listener, so it is the one that yields.
 *
 * @param {KeyboardEvent} event - DOM keyboard event to inspect.
 * @returns {boolean} `true` when the event must be ignored entirely.
 */
export function isKeydownSuppressed(event: KeyboardEvent): boolean {
  return event.defaultPrevented || event.repeat || event.isComposing;
}

/**
 * Evaluates a handler's static or live-state availability gate.
 *
 * @param {PfKeyHandlerEntry} entry - Registered handler entry to inspect.
 * @returns {boolean} `true` when invocation is currently disabled.
 * @throws {unknown} Propagates an exception raised by a screen-owned predicate.
 */
function isHandlerDisabled(entry: PfKeyHandlerEntry): boolean {
  const { disabled } = entry;

  return typeof disabled === 'function' ? disabled() : disabled === true;
}

/**
 * Evaluates a handler's static or live-state in-flight report.
 *
 * Assumptions: an omitted `busy` answers `undefined` rather than `false`, and the two are not
 * interchangeable here. {@link PfKeyBinding.busy} carries the distinction to the renderer, which
 * reserves the affordance's box for a key that reports `false` and reserves nothing for a key that
 * reports nothing — so collapsing them in this function would take a rendering decision away from
 * the component that owns it.
 *
 * Assumptions: a predicate is evaluated on every call, exactly as {@link isHandlerDisabled}
 * evaluates its own. Both are consulted from `createBindings` during render and from `invoke` at
 * dispatch, so a screen answering from a ref sees the ref's value at the moment of the question
 * rather than the value it held when the map was built.
 *
 * @param {PfKeyHandlerEntry} entry - Registered handler entry to inspect.
 * @returns {boolean | undefined} `true` or `false` when the screen reports on this key, and
 * `undefined` when it does not report at all.
 * @throws {unknown} Propagates an exception raised by a screen-owned predicate.
 */
function resolveHandlerBusy(entry: PfKeyHandlerEntry): boolean | undefined {
  const { busy } = entry;

  if (busy === undefined) {
    return undefined;
  }

  return typeof busy === 'function' ? busy() : busy;
}

/**
 * Selects presentation semantics for one registered handler.
 *
 * @param {CicsAid} aid - Canonical AID represented by the handler.
 * @param {PfKeyHandlerEntry} entry - Screen-owned handler metadata.
 * @returns {PfKeyAction} Explicit, shared-default, or screen-defined action.
 */
function resolveAction(aid: CicsAid, entry: PfKeyHandlerEntry): PfKeyAction {
  return entry.action ?? DEFAULT_PF_KEY_ACTIONS[aid] ?? 'screen-defined';
}

// Refactoring Rationale: Literal `EVALUATE EIBAID` callers such as
// `COMEN01C.cbl:L99-L103` report invalid AIDs through the screen message channel
// rather than throwing, so rejection remains recoverable UI state.
// Alternatives Considered: Symbolic callers sometimes coerce an invalid AID to
// ENTER (`COACTUPC.cbl:L914-L916`), but translating an unknown web key into a
// submit could perform a write; reporting the source message is safer and
// preserves the explicit `WHEN OTHER` behavior.
/**
 * Reports a recognized but unavailable AID and restores the screen's input
 * focus when the caller supplied a destination.
 *
 * @param {CicsAid} aid - Canonical AID that could not be dispatched.
 * @param {PfKeyRejectionReason} reason - Availability failure classification.
 * @param {UsePfKeysOptions} options - Current reporting and focus options.
 * @returns {void} Completion is represented through caller-owned side effects.
 * @throws {unknown} Propagates errors raised by the reporting or focus hooks.
 */
function reportRejectedAid(
  aid: CicsAid,
  reason: PfKeyRejectionReason,
  options: UsePfKeysOptions,
): void {
  const focusTarget = options.restoreFocusRef?.current;
  const rejection: PfKeyRejection = {
    aid,
    message: INVALID_KEY_PRESSED,
    reason,
    severity: 'error',
  };

  options.onInvalidKey?.(rejection);
  focusTarget?.focus();
}

/**
 * Converts the sparse handler map into ordered metadata for `PfKeyBar`.
 *
 * @param {PfKeyHandlerMap} handlers - Screen-owned AID registrations.
 * @param {UsePfKeysOptions} options - Current global enabled state.
 * @returns {readonly PfKeyBinding[]} Ordered render metadata for registered
 * handlers only.
 * @throws {unknown} Propagates an exception raised by a disabled or busy predicate.
 */
function createBindings(
  handlers: PfKeyHandlerMap,
  options: UsePfKeysOptions,
): readonly PfKeyBinding[] {
  const bindings: PfKeyBinding[] = [];
  const screenEnabled = options.enabled !== false;

  for (const aid of CICS_AIDS) {
    const entry = handlers[aid];

    if (entry === undefined) {
      continue;
    }

    // Assumptions: the two optional members are spread conditionally rather than assigned a
    // possibly-`undefined` value, because `exactOptionalPropertyTypes` in `ui/tsconfig.json`
    // distinguishes an omitted property from one explicitly set to `undefined` — and that
    // distinction is exactly what `PfKeyBinding.busy` and `PfKeyBinding.risk` carry, so writing
    // `{ busy: undefined }` would both fail to compile and, if it did compile, defeat the contract.
    const busy = resolveHandlerBusy(entry);

    bindings.push({
      action: resolveAction(aid, entry),
      aid,
      enabled: screenEnabled && !isHandlerDisabled(entry),
      label: entry.label ?? '',
      ...(busy === undefined ? {} : { busy }),
      ...(entry.risk === undefined ? {} : { risk: entry.risk }),
    });
  }

  return bindings;
}

/**
 * Registers the active screen's PF-key handlers on a real DOM keydown source.
 *
 * The returned `invoke` callback is stable across renders and is also suitable
 * for `PfKeyBar` button clicks. Both input paths read the latest handler map,
 * evaluate live disabled predicates, report invalid AIDs through the imported
 * COBOL message, and optionally restore focus.
 *
 * Assumptions: dispatch declines an AID THREE ways and only two of them are reported. An unmapped
 * AID and a disabled one raise {@link PfKeyRejection} carrying the baseline's own invalid-key text;
 * an AID whose own turn is still in flight — {@link PfKeyHandlerEntry.busy} — is declined with no
 * message at all, because the reference inhibited the keyboard for the duration of a task and
 * discarded what was typed into it rather than answering it. The rationale is written out at the
 * gate itself, inside `invoke`.
 *
 * Alternatives Considered: A synthetic event bus was rejected because it could
 * diverge from the browser path used by the keyboard-first application.
 * Assumptions: The Vitest contract drives genuine DOM keyboard events through
 * `@testing-library/user-event`, so production and test dispatch must share that
 * same event path.
 *
 * Assumptions: The keyboard path is deliberately narrower than the imperative
 * one, and the difference is the browser rather than the screen. `invoke` runs the
 * validation chain and nothing else, because a click has already been resolved to
 * one control. A keydown passes two further gates first - the event-state gate of
 * {@link isKeydownSuppressed} and the target gate of
 * {@link isAidClaimedByEventTarget} - because a key press arrives with context a
 * click does not have: it may be an auto-repeat, an input-method composition, an
 * event another listener has already handled, or a key the focused control acts
 * on itself. Neither gate exists in the baseline, because a terminal had no
 * concept of any of the four.
 *
 * @param {PfKeyHandlerMap} handlers - Sparse handlers owned by the active
 * screen.
 * @param {UsePfKeysOptions} options - Optional listener, reporting, and focus
 * configuration.
 * @returns {UsePfKeysResult} Ordered bindings and a stable imperative dispatch
 * function.
 * @throws {unknown} Propagates screen callback, predicate, reporting, or focus
 * errors so the application's error boundary can handle programming failures.
 */
export function usePfKeys(
  handlers: PfKeyHandlerMap,
  options: UsePfKeysOptions = {},
): UsePfKeysResult {
  // Trade-offs: Reading current callbacks through a ref adds one indirection,
  // but avoids replacing the DOM listener whenever a screen recreates its
  // handler map and guarantees cleanup removes the same registered function.
  const currentState = useRef<CurrentPfKeyState>({ handlers, options });

  useEffect(
    /**
     * Publishes the latest render's handlers and options to the stable readers.
     *
     * Refactoring Rationale: this assignment used to run during render, which is
     * a write to a value outside the render's own output. React may start a
     * render and discard it, so a ref written that way can be left holding
     * handlers from a render that never committed - and the listener installed
     * below would then dispatch into them. Publishing on commit means the ref
     * only ever holds state that reached the DOM.
     *
     * Alternatives Considered: a layout effect, and an insertion effect. Both run
     * earlier in the commit and would close the one-commit window in which the
     * ref still holds the previous render's values. A layout effect is rejected
     * because it warns when a tree is rendered on a server, which the listener
     * effect below explicitly tolerates by bailing out when there is no document;
     * an insertion effect is rejected because the library documents it for
     * style-injection use only, so borrowing it here would rely on behaviour
     * outside its stated contract. The window a passive effect leaves is between
     * commit and the effect flush that precedes the next paint, and no keyboard
     * or click event can be delivered inside it, so the two earlier hooks buy
     * nothing this one does not already give.
     * @returns {void} Completion is the updated ref contents.
     */
    function publishCurrentPfKeyState(): void {
      currentState.current = { handlers, options };
    },
    [handlers, options],
  );

  const invoke = useCallback(
    /**
     * Dispatches an AID against values from the most recent render.
     *
     * @param {CicsAid} aid - Canonical AID to validate and dispatch.
     * @returns {boolean} `true` when an enabled handler ran; otherwise `false`.
     * @throws {unknown} Propagates screen callback, predicate, reporting, or
     * focus errors.
     */
    function invokePfKey(aid: CicsAid): boolean {
      const state = currentState.current;

      if (state.options.enabled === false) {
        return false;
      }

      const entry = state.handlers[aid];

      if (entry === undefined) {
        reportRejectedAid(aid, 'unmapped', state.options);
        return false;
      }

      if (isHandlerDisabled(entry)) {
        reportRejectedAid(aid, 'disabled', state.options);
        return false;
      }

      // Assumptions: a key whose own turn is still in flight is declined SILENTLY, with no
      // rejection reported and no focus moved. The reference behaves the same way and for the same
      // reason: a 3270 inhibits the keyboard for the duration of a task, so an attention key
      // pressed while the transaction was running never reached a program and no message was ever
      // painted for it. Reporting `INVALID_KEY_PRESSED` here would put a sentence on the error band
      // saying the key does not work, when the key works and the operator was early.
      // Refactoring Rationale: this gate is in `invoke` rather than only in the rendered control,
      // even though the design system's own button already swallows a click while it is loading
      // (`ui/node_modules/antd/lib/button/Button.js` L195-L202 returns before `onClick`). Leaving
      // it there alone would have protected the pointer and left the keyboard unprotected, and the
      // keyboard is the fidelity-bearing path — a measured double-submit produced two identical
      // reads 400 ms apart and desynchronised a list, and the same two presses on F8 would do it.
      // Trade-offs: a screen that reports busy and forgets to clear it makes the key inert. The
      // alternative, queueing the press and replaying it when the turn returns, was rejected: the
      // terminal discarded inhibited input rather than buffering it, and replaying a delete after
      // its own turn came back is the last behaviour a destructive key should have.
      if (resolveHandlerBusy(entry) === true) {
        return false;
      }

      entry.onInvoke(aid);
      return true;
    },
    [],
  );

  const target = options.target ?? null;

  useEffect(
    /**
     * Installs one listener for the current target and removes it on cleanup.
     *
     * @returns {PfKeyListenerCleanup | undefined} Cleanup callback in a DOM
     * environment, or `undefined` during server rendering.
     */
    function subscribeToPfKeys(): PfKeyListenerCleanup | undefined {
      if (typeof document === 'undefined') {
        return undefined;
      }

      const eventTarget = target ?? document;

      /**
       * Normalizes and dispatches one bubbling DOM keydown event.
       *
       * @param {Event} event - Event received from the configured target.
       * @returns {void} Dispatch and rejection effects are caller-owned.
       * @throws {unknown} Propagates errors from the active screen callbacks.
       */
      function handleKeydown(event: Event): void {
        if (!(event instanceof KeyboardEvent)) {
          return;
        }

        if (isKeydownSuppressed(event)) {
          return;
        }

        const state = currentState.current;

        if (state.options.enabled === false) {
          return;
        }

        // Refactoring Rationale: Normalizing before screen dispatch mirrors the
        // `PERFORM YYYY-STORE-PFKEY` ordering in `COCRDLIC.cbl:L349-L350` and
        // `COACTUPC.cbl:L898-L899`; branching on raw browser keys would recreate
        // the legacy split between symbolic and literal dispatch families.
        const aid = resolveAidFromKeyboardEvent(event);

        if (aid === undefined) {
          return;
        }

        // Refactoring Rationale: the target policy is applied BEFORE the handler
        // lookup, so a screen that registers ENTER still leaves a focused
        // control's own activation intact. Applying it after the lookup would
        // have made the outcome depend on whether the screen happened to bind
        // ENTER, which is not a distinction the user can see or predict.
        if (isAidClaimedByEventTarget(aid, event.target)) {
          return;
        }

        const entry = state.handlers[aid];

        // Trade-offs: Claiming browser-reserved keys such as F3, F5, F7, and
        // F12 removes native browser behavior. That cost is accepted only where
        // the active screen registers one of those primary 3270 workflow verbs,
        // so an unregistered normalized AID retains its browser default.
        if (entry !== undefined) {
          event.preventDefault();
        }

        invoke(aid);
      }

      eventTarget.addEventListener('keydown', handleKeydown);

      /**
       * Removes exactly the listener installed by this effect execution.
       *
       * @returns {void} The configured target no longer receives this hook's
       * keydown dispatch.
       */
      function unsubscribeFromPfKeys(): void {
        eventTarget.removeEventListener('keydown', handleKeydown);
      }

      return unsubscribeFromPfKeys;
    },
    [invoke, target],
  );

  return {
    bindings: createBindings(handlers, options),
    invoke,
  };
}
