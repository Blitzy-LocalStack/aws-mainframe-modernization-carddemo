/**
 * @file Contains a failed screen inside the shell instead of letting it blank the application.
 *
 * Purpose
 * -------
 * Every screen but sign-on is loaded on first navigation to it, so the code for a screen arrives
 * over the network long after the application started. That fetch can fail for reasons no screen can
 * answer for, and the commonest one is ordinary: a deployment replaces the hashed asset names while a
 * tab is still holding the previous `index.html`, so the next navigation asks for a file that no
 * longer exists. This module renders the shell's route content inside an error boundary, so such a
 * failure replaces the CONTENT and leaves the title band, the message row and the key legend where
 * they were.
 *
 * Why a boundary is needed at all when the tree already has Suspense
 * -----------------------------------------------------------------
 * Refactoring Rationale: `ui/src/router.tsx` documented its `Suspense` as the surface a failed chunk
 * arrives at, and that is not what `Suspense` does. It answers a PENDING promise -- it renders its
 * fallback while one is outstanding and its children once it settles -- and a REJECTED one is thrown
 * during render, which propagates to the nearest error boundary exactly as any other render error
 * does. There was no error boundary anywhere above the twenty lazily loaded screens, so the throw
 * reached the root, React unmounted the whole tree, and an operator was left with a blank document
 * carrying no sentence at all. The claim in the router was the reason nobody had noticed: it named a
 * mechanism that appeared to cover the case.
 *
 * Alternatives Considered: `react-error-boundary`, which packages this class and adds a hook API.
 * Rejected because a boundary is the one thing React still requires a class component for -- there is
 * no hook equivalent of `getDerivedStateFromError` -- so the dependency would buy roughly the forty
 * lines below and add a package to the bundle and to the review surface of a card-management
 * application.
 *
 * Alternatives Considered: catching the rejection inside each `lazy` adapter and resolving to a
 * placeholder module instead of rejecting. Rejected because it would turn a load failure into a
 * screen that renders, so a route would report success with nothing behind it, and because the
 * adapters would then each carry their own copy of the recovery decision -- twenty places for one
 * policy.
 *
 * Trade-offs: the boundary catches every render error in the content region, not only a failed
 * import, and that width is deliberate. A screen that throws while rendering leaves an operator in
 * exactly the same position as one whose code never arrived, and the alternative -- narrowing by
 * inspecting the error -- would mean matching on a bundler's chunk-load message, which is neither
 * stable across builds nor part of any contract. What the width costs is diagnostic precision on the
 * page, and that is recovered in the console rather than in front of the operator; see
 * {@link ShellContentBoundary.componentDidCatch}.
 */

import { Button, Result } from 'antd';
import { Component } from 'react';
import type { ErrorInfo, ReactElement, ReactNode } from 'react';
import { Outlet, useLocation } from 'react-router';

import { SCREEN_LOAD_FAILURE_MESSAGES } from '../messages/messages';

/**
 * Reloads the current document, which is the one recovery that re-attempts the failed import.
 *
 * WHY this is a document reload and not an in-place retry -- Assumptions: re-rendering the same
 * screen cannot work, and the reason is in React rather than in this application. `lazy()` stores
 * the outcome of its loader on the payload it wraps: `lazyInitializer` in `react@19.2.8` sets the
 * payload's status to rejected and re-throws the STORED error on every later render, so a remount
 * presents this surface again without a request leaving the browser. The module map behaves the same
 * way one level down -- a specifier whose fetch failed is not re-fetched -- so even a fresh component
 * would not re-attempt it. A reload discards both caches with the document, re-fetches `index.html`,
 * and so picks up the new asset names that the commonest cause of this failure has just introduced.
 *
 * Alternatives Considered: appending a cache-busting query to the dynamic specifier so the import
 * misses both caches. Rejected because a specifier the bundler cannot analyse statically is not
 * chunked at all, which would trade this failure for a bundle that no longer splits.
 * @returns {void} Nothing; the document navigates.
 */
function reloadDocument(): void {
  window.location.reload();
}

/** Props {@link ShellContentBoundary} accepts. */
export interface ShellContentBoundaryProps {
  /** The route content to render while no failure has been caught. */
  readonly children: ReactNode;

  /**
   * Value whose change clears a caught failure, so a navigation is not blocked by the last one.
   *
   * Assumptions: the CALLER supplies this rather than the boundary reading the location itself,
   * because a class component cannot call a hook. {@link BoundedShellOutlet} passes the history
   * entry's key, which is what makes any navigation -- including one back to the same address --
   * clear the surface.
   */
  readonly recoveryKey: string;

  /**
   * Recovery the single control performs, defaulting to reloading the document.
   *
   * Assumptions: it is injectable only so a test can observe the control without navigating jsdom,
   * which implements no navigation and reports the attempt as an unimplemented feature. Production
   * callers leave it unset and get {@link reloadDocument}.
   */
  readonly onRecover?: () => void;
}

/** State {@link ShellContentBoundary} carries: whether a failure has been caught. */
interface ShellContentBoundaryState {
  /** True once a descendant threw during render, until {@link ShellContentBoundaryProps.recoveryKey} changes. */
  readonly failed: boolean;
}

/**
 * Renders its children, or a bounded failure surface once a descendant has thrown.
 *
 * Assumptions: a class is not a style choice here. `getDerivedStateFromError` and
 * `componentDidCatch` are the only way React reports a render error to application code, and neither
 * has a hook equivalent, so every error boundary in a React application is a class.
 *
 * Assumptions: this component publishes NOTHING to the shell slot, so the failing screen's
 * withdrawn publication leaves the shell showing its own defaults -- no transaction identifier for a
 * screen that is not there, and no key legend for actions that cannot be performed. Publishing a
 * substitute identity was considered and rejected: the header's identifiers are transcribed from the
 * reference's own mapsets, and inventing one for a surface the reference has no equivalent of would
 * put a value in the audit trail that names no program.
 */
export class ShellContentBoundary extends Component<
  ShellContentBoundaryProps,
  ShellContentBoundaryState
> {
  /**
   * Starts in the healthy state, so the first render passes the children through untouched.
   * @param {ShellContentBoundaryProps} props - Props described by {@link ShellContentBoundaryProps}.
   */
  public constructor(props: ShellContentBoundaryProps) {
    super(props);
    this.state = { failed: false };
  }

  /**
   * Records that a descendant threw, so the next render paints the failure surface.
   *
   * Assumptions: the error itself is deliberately NOT held in state. Nothing rendered below reads
   * it -- the surface says the same sentence whatever threw -- and state is what a future edit
   * reaches for when it wants to display something, so not holding it is what keeps a module path or
   * a stack out of the document by construction. It reaches the console instead, from
   * {@link ShellContentBoundary.componentDidCatch}.
   * @returns {ShellContentBoundaryState} The failed state.
   */
  public static getDerivedStateFromError(): ShellContentBoundaryState {
    return { failed: true };
  }

  /**
   * Reports the caught failure to the browser console, where a developer can read all of it.
   *
   * Assumptions: the split between what is logged and what is rendered is the same one
   * `ui/src/main.tsx` makes for a start-up failure, and for the same reason. The error message, the
   * failing module's path and the component stack are internal: an operator can act on none of them,
   * and rendering any of them would publish deployment detail onto a page and into every screenshot
   * of it. A developer reading the console gets the whole of it.
   * @param {Error} error - The error a descendant threw during render.
   * @param {ErrorInfo} info - React's report of where it was thrown, carrying the component stack.
   * @returns {void} Nothing; the failure is reported as a side effect.
   */
  public override componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('carddemo: a screen failed to render', error, info.componentStack);
  }

  /**
   * Clears a caught failure when the caller reports a navigation.
   *
   * Refactoring Rationale: the reset is driven by a CHANGED PROP rather than by keying this
   * component on the location, which is the shorter spelling and the wrong one here. A `key` bound to
   * the location would remount this boundary and everything under it on every navigation, including
   * the parameter-only navigations react-router deliberately serves by re-rendering one mounted
   * screen -- so a card detail moving from one selector to the next would lose its screen state for
   * no reason connected to this file.
   *
   * Assumptions: the guard tests the failed state first, so a healthy navigation performs no
   * `setState` at all and cannot loop.
   * @param {ShellContentBoundaryProps} previous - Props this component rendered with last.
   * @returns {void} Nothing; the state is cleared as a side effect.
   */
  public override componentDidUpdate(previous: ShellContentBoundaryProps): void {
    if (this.state.failed && previous.recoveryKey !== this.props.recoveryKey) {
      this.setState({ failed: false });
    }
  }

  /**
   * Renders the route content, or the failure surface once a descendant has thrown.
   *
   * Assumptions: the heading is the reference's own wording for a failure it cannot continue past and
   * the sentence beneath it is authored, both taken from `ui/src/messages/messages.ts` rather than
   * spelled here -- an operator-visible sentence with two copies drifts the first time one is
   * corrected.
   * @returns {ReactNode} The children, or the bounded failure surface.
   */
  public override render(): ReactNode {
    if (!this.state.failed) {
      return this.props.children;
    }

    const recover = this.props.onRecover ?? reloadDocument;

    return (
      <Result
        status="error"
        title={SCREEN_LOAD_FAILURE_MESSAGES.TITLE}
        subTitle={SCREEN_LOAD_FAILURE_MESSAGES.EXPLANATION}
        extra={
          <Button type="primary" onClick={recover}>
            {SCREEN_LOAD_FAILURE_MESSAGES.RELOAD_CONTROL}
          </Button>
        }
      />
    );
  }
}

/**
 * Route element that renders the shell's outlet inside {@link ShellContentBoundary}.
 *
 * Assumptions: this is a pathless layout route nested INSIDE the shell's own layout route, which is
 * what makes the containment work: the shell renders this element in its content region, so a caught
 * failure replaces that region and the three delegated zones around it stay mounted. A boundary
 * placed above the shell would catch the same errors and blank the frame with them, which is the
 * state this replaces.
 *
 * Assumptions: it wraps the whole framed subtree -- sign-on as well as the guarded screens -- rather
 * than only the lazily loaded ones. Sign-on is imported eagerly and so cannot fail to load, but it
 * can still throw while rendering, and a boundary that covered every screen except the first one an
 * operator meets would be the one gap nobody tests.
 * @returns {ReactElement} The outlet, contained by the boundary.
 */
export function BoundedShellOutlet(): ReactElement {
  const location = useLocation();

  return (
    <ShellContentBoundary recoveryKey={location.key}>
      <Outlet />
    </ShellContentBoundary>
  );
}
