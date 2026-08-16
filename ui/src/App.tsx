/**
 * @file The application boundary: the one place the design-system theme is injected and the one
 * place the persistent frame around every screen is mounted.
 *
 * Purpose
 * -------
 * Own the ONE concern that is shared by all 21 migrated screens and belongs to none of them: the
 * `ConfigProvider` that carries the BMS-to-antd token bridge. The route tree is `ui/src/router.tsx`'s,
 * and so is the single mount of the shell in `ui/src/layout/` -- the component that paints the four
 * persistent zones the baseline showed on every screen -- which that file declares as a layout route.
 * This module renders the router and adds nothing else.
 *
 * Boundary
 * --------
 * Assumptions: this module holds no state, issues no request and knows no route. Anything a screen
 * needs from the frame it delegates through `useShellSlot`, so a screen cannot acquire a dependency
 * on this file and this file cannot acquire one on a screen.
 */

import type { ReactElement } from 'react';

import { ConfigProvider } from 'antd';

import { CardDemoRouter } from './router';
import { cardDemoTheme } from './theme/antdTheme';

/**
 * Applies the single design-system theme around the CardDemo route tree.
 * @returns {ReactElement} The themed application; the frame is mounted by the route table.
 */
export function App(): ReactElement {
  return (
    // Assumptions: theming is injected EXACTLY ONCE, here, and never per screen. antd 6 resolves
    //   this provider's tokens into CSS variables on the subtree it wraps, so one provider above
    //   the router reaches every screen the router can reach. A second provider lower down would
    //   not merely be redundant: the nearer one wins for the subtree beneath it, so two screens
    //   could render the same token at two values and the design-system rule that every value
    //   trace to a token would still hold in each file while the rendered application disagreed
    //   with itself. Keeping the injection point single is what makes `cardDemoTheme` the whole
    //   of the answer to "what colour is this".
    <ConfigProvider theme={cardDemoTheme}>
      {/*
        Refactoring Rationale: this file used to build a frame of its own out of generic `Layout`,
        `Layout.Header` and `Layout.Footer` primitives while the shell component was imported by nothing, so the
        frame an operator actually saw was this file's approximation of it: an application title, no
        transaction identifier, no program name, no server clock, no message line and no key legend --
        and the screens already authored to omit their own title band on the ground that the shell
        supplies it had no band at all. That frame is gone and no replacement is composed here.

        ⚠️ Refactoring Rationale: one remedy for that finding mounted the real shell HERE, as
        the shell element wrapping the router as its `children`, and it is withdrawn in favour of the
        layout route in `ui/src/router.tsx`. Both make the shell apply, and it accepts either shape -- `children`
        when present, otherwise react-router's `Outlet` -- which is exactly why keeping both was not
        harmless: a shell given `children` never reaches its outlet, so the outer mount rendered the
        router and the inner layout route rendered the matched screen, and every guarded screen was
        framed TWICE. Two `app-shell` regions, two banners, two contentinfo landmarks, two skip links,
        and both shells reading the one publication a screen makes -- so two title bands and two live
        regions announcing one message.

        Assumptions: the layout route is the mount that survives, for the reason it records: mounted
        there the shell is instantiated once for the whole tree and renders `<Outlet />`, so the frame
        persists across navigation between children rather than being remounted per screen, and a route
        that one day needs a different frame can say so in the table instead of here. What this file
        keeps is the theme, which genuinely is a property of the whole application and of no route.

        Trade-offs: a screen therefore cannot replace the frame, which is the intended loss -- the 3270
        original had no such affordance either, and a screen that could would be able to hide which
        transaction an operator was in. What a screen CAN do is delegate zone content to it through
        `useShellSlot`, which is how each screen supplies its own identity, message and key legend
        without composing a second copy of the chrome.
      */}
      <CardDemoRouter />
    </ConfigProvider>
  );
}
