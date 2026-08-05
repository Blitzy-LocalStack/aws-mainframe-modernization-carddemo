/**
 * Centralizes CICS attention-identifier (AID) keyboard semantics for the React
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

import { useCallback, useEffect, useRef } from "react";
import type { RefObject } from "react";

import { INVALID_KEY_PRESSED } from "../messages/messages";

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
  "ENTER",
  "CLEAR",
  "PA1",
  "PA2",
  "PFK01",
  "PFK02",
  "PFK03",
  "PFK04",
  "PFK05",
  "PFK06",
  "PFK07",
  "PFK08",
  "PFK09",
  "PFK10",
  "PFK11",
  "PFK12",
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
  F13: "PFK01",
  F14: "PFK02",
  F15: "PFK03",
  F16: "PFK04",
  F17: "PFK05",
  F18: "PFK06",
  F19: "PFK07",
  F20: "PFK08",
  F21: "PFK09",
  F22: "PFK10",
  F23: "PFK11",
  F24: "PFK12",
});

/**
 * Browser `KeyboardEvent.key` values that normalize to CICS AIDs.
 *
 * Alternatives Considered: CLEAR, PA1, and PA2 remain in `CicsAid` but have no
 * invented browser shortcuts. The copybook normalizes those terminal AIDs,
 * while measured usage across all 21 online programs is zero, so assigning
 * arbitrary web keys would create behavior absent from the source application.
 */
export const KEYBOARD_KEY_TO_AID: Readonly<Record<string, CicsAid>> =
  Object.freeze({
    Enter: "ENTER",
    F1: "PFK01",
    F2: "PFK02",
    F3: "PFK03",
    F4: "PFK04",
    F5: "PFK05",
    F6: "PFK06",
    F7: "PFK07",
    F8: "PFK08",
    F9: "PFK09",
    F10: "PFK10",
    F11: "PFK11",
    F12: "PFK12",
    ...PF_KEY_ALIASES,
  });

/**
 * Semantic action represented by a registered PF-key handler.
 */
export type PfKeyAction =
  | "submit"
  | "back"
  | "clear"
  | "save"
  | "page-backward"
  | "page-forward"
  | "cancel"
  | "screen-defined";

/**
 * Default semantic actions observed across the online COBOL programs.
 *
 * Keys omitted from this partial map resolve to `screen-defined`, allowing
 * screens such as transaction-type maintenance to bind PF2 and PF10 without
 * assigning misleading global semantics.
 */
export const DEFAULT_PF_KEY_ACTIONS: Readonly<
  Partial<Record<CicsAid, PfKeyAction>>
> = Object.freeze({
  ENTER: "submit",
  PFK03: "back",
  PFK04: "clear",
  PFK05: "save",
  PFK07: "page-backward",
  PFK08: "page-forward",
  PFK12: "cancel",
});

/**
 * Reason that a recognized CICS AID could not be dispatched.
 */
export type PfKeyRejectionReason = "unmapped" | "disabled";

/**
 * Error-channel payload emitted for a recognized but unavailable AID.
 */
export interface PfKeyRejection {
  /** Canonical AID that the browser key normalized to. */
  readonly aid: CicsAid;
  /** Byte-preserved invalid-key text imported from the message catalog. */
  readonly message: typeof INVALID_KEY_PRESSED;
  /** Message severity expected by screen-level status renderers. */
  readonly severity: "error";
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
}

/**
 * Sparse set of AID handlers registered by the active screen.
 */
export type PfKeyHandlerMap = Partial<
  Readonly<Record<CicsAid, PfKeyHandlerEntry>>
>;

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
}

/**
 * Configuration for keyboard-listener scope and invalid-key reporting.
 */
export interface UsePfKeysOptions {
  /** Disables all dispatch without unregistering the screen's handler map. */
  readonly enabled?: boolean;
  /** Optional event target ref; a missing target falls back to `document`. */
  readonly target?: RefObject<EventTarget | null>;
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
 * @param {string} key - Browser `KeyboardEvent.key` value to normalize.
 * @returns {CicsAid | undefined} Matching CICS AID, or `undefined` when the
 * key is not a terminal attention identifier represented on the web.
 */
export function resolveAid(key: string): CicsAid | undefined {
  return KEYBOARD_KEY_TO_AID[key];
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
export function resolveAidFromKeyboardEvent(
  event: KeyboardEvent,
): CicsAid | undefined {
  if (event.altKey || event.ctrlKey || event.metaKey) {
    return undefined;
  }

  return resolveAid(event.key);
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

  return typeof disabled === "function" ? disabled() : disabled === true;
}

/**
 * Selects presentation semantics for one registered handler.
 *
 * @param {CicsAid} aid - Canonical AID represented by the handler.
 * @param {PfKeyHandlerEntry} entry - Screen-owned handler metadata.
 * @returns {PfKeyAction} Explicit, shared-default, or screen-defined action.
 */
function resolveAction(aid: CicsAid, entry: PfKeyHandlerEntry): PfKeyAction {
  return entry.action ?? DEFAULT_PF_KEY_ACTIONS[aid] ?? "screen-defined";
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
    severity: "error",
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
 * @throws {unknown} Propagates an exception raised by a disabled predicate.
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

    bindings.push({
      action: resolveAction(aid, entry),
      aid,
      enabled: screenEnabled && !isHandlerDisabled(entry),
      label: entry.label ?? "",
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
 * Alternatives Considered: A synthetic event bus was rejected because it could
 * diverge from the browser path used by the keyboard-first application.
 * Assumptions: The Vitest contract drives genuine DOM keyboard events through
 * `@testing-library/user-event`, so production and test dispatch must share that
 * same event path.
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
  currentState.current = { handlers, options };

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
        reportRejectedAid(aid, "unmapped", state.options);
        return false;
      }

      if (isHandlerDisabled(entry)) {
        reportRejectedAid(aid, "disabled", state.options);
        return false;
      }

      entry.onInvoke(aid);
      return true;
    },
    [],
  );

  const targetRef = options.target;

  useEffect(
    /**
     * Installs one listener for the current target ref and removes it on cleanup.
     *
     * @returns {PfKeyListenerCleanup | undefined} Cleanup callback in a DOM
     * environment, or `undefined` during server rendering.
     */
    function subscribeToPfKeys(): PfKeyListenerCleanup | undefined {
      if (typeof document === "undefined") {
        return undefined;
      }

      const eventTarget = targetRef?.current ?? document;

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

      eventTarget.addEventListener("keydown", handleKeydown);

      /**
       * Removes exactly the listener installed by this effect execution.
       *
       * @returns {void} The configured target no longer receives this hook's
       * keydown dispatch.
       */
      function unsubscribeFromPfKeys(): void {
        eventTarget.removeEventListener("keydown", handleKeydown);
      }

      return unsubscribeFromPfKeys;
    },
    [invoke, targetRef],
  );

  return {
    bindings: createBindings(handlers, options),
    invoke,
  };
}
