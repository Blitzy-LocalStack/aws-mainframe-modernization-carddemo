/**
 * @file The application boundary: the one place the design-system theme is injected, and the one
 * place the route tree is rendered.
 *
 * Purpose
 * -------
 * Own the ONE concern that is shared by all 21 migrated screens and belongs to none of them: the
 * `ConfigProvider` that carries the BMS-to-antd token bridge. The route tree is `ui/src/router.tsx`'s,
 * and so is every mount of the shell in `ui/src/layout/` -- the component that paints the four
 * persistent zones the baseline showed on every screen -- which that file declares as two sibling
 * layout routes, one per access branch. This module renders the router and adds nothing else.
 *
 * Refactoring Rationale: the summary line above previously claimed this file was where the persistent
 * frame is mounted, which contradicted the paragraph immediately below it and stopped being true when
 * that mount moved to the layout routes recorded further down. A header that disagrees with its own
 * body is worse than a missing one, because a reader who trusts it looks for the frame here and finds
 * a provider.
 *
 * Parameters
 * ----------
 * Not applicable. This module declares one component and takes no inputs of its own: it reads no
 * environment variable, no query string and no storage key. All three of its collaborators -- the
 * theme, the router instance and the provider component -- are resolved statically by the imports
 * below, so there is nothing here for a caller to configure.
 *
 * Return values
 * -------------
 * {@link App} returns the themed application element, and is documented at its declaration. The
 * module exports nothing else -- no constant, no type, no helper -- because any further export from
 * the composition root would be a dependency a screen could acquire on it.
 *
 * Exceptions or errors
 * --------------------
 * This module raises none, and deliberately catches none either. Three collaborators cover the
 * failure modes that reach an operator: `ui/src/main.tsx` reports a start-up failure that prevents
 * mounting at all, `ui/src/router.tsx` resolves an unmatched path to a result screen, and the two
 * screens whose reference programs carry an abend routine render the `ABEND-DATA` fields of
 * `app/cpy/CSMSG02Y.cpy` L21-L29 themselves. One failure mode is covered by NONE of them and is named
 * so it is not mistaken for handled: a screen chunk whose lazy import rejects. The `Suspense`
 * boundary in the route tree supplies a pending fallback only and never sees the rejection, so what
 * renders is the routing package's own default boundary -- an untranslated heading carrying the error
 * message and its stack, outside the frame and outside the message catalogue. The note at the
 * provider below records why this module still adds no boundary of its own.
 *
 * Boundary
 * --------
 * Assumptions: this module holds no state, issues no request and knows no route. Anything a screen
 * needs from the frame it delegates through `useShellSlot`, so a screen cannot acquire a dependency
 * on this file and this file cannot acquire one on a screen.
 */

/*
 * WHY : Assumptions: the type is imported with `import type` because `ui/tsconfig.json` enables
 *       `verbatimModuleSyntax`, and esbuild -- which transforms this tree under both Vite and Vitest
 *       -- compiles one file at a time and cannot look across the module boundary to decide whether
 *       `ReactElement` is a type or a value. Written as an ordinary import it can survive into the
 *       bundle as a runtime import of a name that exports no value, failing when the browser
 *       evaluates the module rather than when anything checks it. No default `React` import appears
 *       for the separate reason that `jsx: "react-jsx"` selects the automatic runtime: the JSX below
 *       compiles without one, and `noUnusedLocals` rejects one added out of habit.
 */
import type { ReactElement } from 'react';

import { useLayoutEffect } from 'react';

/*
 * WHY : Assumptions: the provider is imported from the PACKAGE ROOT, never from a deep entry point
 *       inside the published package, and at this version that is what makes one theme reach every
 *       component. antd 6 defaults to CSS-variable theming: the provider emits the token layers as
 *       custom properties under a named scope and each component resolves its values from that scope
 *       by class name. Reaching past the root bypasses the package `exports` map the bundler relies
 *       on to keep a single copy of the library's style registry, so a second registry can be
 *       instantiated -- and a component served by the second one reads no scope at all and silently
 *       renders library defaults. The failure is a wrong colour on a screen, not a build error, which
 *       is why the discipline is stated rather than left to convention; no module under `ui/src`
 *       writes such a specifier.
 * WHY : Refactoring Rationale: NO React 19 compatibility shim is imported, and the absence is the
 *       decision. Version 5 of this library required a patch package to reconcile React 19's changed
 *       rendering entry points; version 6 removed that requirement, so the patch is deliberately
 *       absent from `ui/package.json`. The pinned antd 6.5.2 peer-declares React and React DOM at 18
 *       or later and is satisfied as published by the pinned React 19.2.8. Reinstating the shim would
 *       add a package whose only remaining job is to reconcile a version pair that no longer
 *       disagrees, and it would do so at the theme provider -- the point where a wrong render path is
 *       hardest to attribute. It is named here so that a reader who remembers the version 5
 *       requirement can see it was considered rather than forgotten.
 */
import { App as AntApp, ConfigProvider, theme } from 'antd';

/*
 * WHY : Assumptions: ONE symbol is imported from the routing package here -- the provider component
 *       -- and the router OBJECT it renders is imported from `ui/src/router.tsx`, which is the only
 *       module that declares routes. That split is the data-router API's own division of labour: the
 *       router object owns the history and the route tree, and the provider merely subscribes a React
 *       tree to it, so the composition root can mount the application without holding an opinion
 *       about any path. This file therefore still names no route and no screen.
 * WHY : Alternatives Considered: the DOM companion package -- the one whose name is this one's with
 *       `-dom` appended, which major version 6 split the browser entry points into and which a reader
 *       familiar with that version reaches for first. It is absent from `ui/package.json` on its own
 *       evidence: no 8.x of it was ever published, and its newest release is a compatibility shim
 *       depending on `react-router@7.18.1`, so requiring it would silently hold routing a major
 *       version behind the pinned 8.3.0 while looking like the more specific choice. Every browser
 *       entry point that justified the original split, this provider included, is exported from the
 *       base package at 8.x. The name is described rather than written so a search proving this
 *       module does not depend on it stays clean.
 */
import { RouterProvider } from 'react-router';

import { cardDemoRouter } from './router';
import { cardDemoTheme } from './theme/antdTheme';

/**
 * Carries the theme's typographic and surface reset onto `document.body`.
 *
 * Purpose: the library's own reset scope reaches only its wrapper and that wrapper's descendants,
 * and `document.body` is neither -- it is their ancestor. A browser measurement of the running
 * application found `document.body` computing `font-family` as `"Times New Roman"`, `color` as
 * `rgb(0, 0, 0)` and `background-color` as `rgba(0, 0, 0, 0)`, with the only rule touching either
 * element across all 43 loaded stylesheets being `html, body { margin: 0 }`. Every value applied
 * here is read from the one provider above, so the theme remains the single source AAP section
 * 0.4.4 requires.
 *
 * Parameters: none. The component takes no props; its inputs are the resolved theme tokens, which
 * it reads from the provider it is rendered inside.
 * @returns {null} Nothing is rendered. The component exists for its effect, so it adds no element
 *   and cannot disturb the height chain the mount point owns.
 *
 * Exceptions or errors: none are raised. `useToken` resolves against the enclosing provider and
 * `document.body` exists by the time any effect runs, so there is no absent collaborator to guard.
 */
function DocumentSurface(): null {
  const { token } = theme.useToken();

  useLayoutEffect(
    /**
     * Applies the resolved tokens to the document body and restores what was there before.
     * @returns {() => void} A cleanup that puts each property back to its previous inline value,
     *   so a test that mounts and unmounts the application leaves no residue behind.
     */
    function applyDocumentSurface(): () => void {
      const { style } = document.body;
      /*
       * Assumptions: the previous INLINE values are captured rather than the computed ones, and
       * restored as inline values. Writing computed values back would convert an inherited or
       * stylesheet-supplied value into an inline override that outlives this component, which is
       * the opposite of a clean teardown.
       */
      const previous = {
        backgroundColor: style.backgroundColor,
        color: style.color,
        fontFamily: style.fontFamily,
        fontSize: style.fontSize,
        lineHeight: style.lineHeight,
      };

      /*
       * Assumptions: the surface token is `colorBgContainer` rather than `colorBgLayout`, because
       * the frame's own `Layout` paints `colorBgContainer` full bleed. Matching it means the strip
       * a browser exposes on overscroll is the same colour as the frame above it instead of a
       * visible seam. Nothing else about the body is touched -- no size, no display, no position --
       * so the one-viewport flex column `ui/index.html` establishes and
       * `ui/src/layout/appShell.test.tsx` asserts is untouched.
       */
      style.backgroundColor = token.colorBgContainer;
      style.color = token.colorText;
      style.fontFamily = token.fontFamily;
      style.fontSize = `${String(token.fontSize)}px`;
      style.lineHeight = String(token.lineHeight);

      /**
       * Restores each body property to the inline value it held before this component mounted.
       * @returns {void} Nothing; the five properties are written back in place.
       */
      return function restoreDocumentSurface(): void {
        style.backgroundColor = previous.backgroundColor;
        style.color = previous.color;
        style.fontFamily = previous.fontFamily;
        style.fontSize = previous.fontSize;
        style.lineHeight = previous.lineHeight;
      };
    },
    [token.colorBgContainer, token.colorText, token.fontFamily, token.fontSize, token.lineHeight],
  );

  return null;
}

/**
 * Applies the single design-system theme around the CardDemo route tree.
 *
 * Purpose: this is the whole of the application's composition. It injects the theme and renders the
 * route tree, and it is the only component in the tree belonging to no screen and no layout.
 *
 * Parameters: none. This component accepts NO props, and that is a contract rather than an omission
 * -- the tag is stated in prose because declaring a parameter the signature does not have would fail
 * `jsdoc/check-param-names`. `ui/src/main.tsx` is its only caller and renders it as `<App />`, so a
 * prop added here could only be supplied by the module that mounts the React root, which is the one
 * place in the tree with no configuration of its own to pass on. Everything a screen needs reaches it
 * through the router or through the frame's own slots, never through this signature.
 *
 * Exceptions or errors: none are raised here. This component renders no data and performs no I/O, so
 * the only failure reachable through it is one a descendant raises, which propagates past it for the
 * reason recorded in the module header above.
 * @returns {ReactElement} The themed application: the provider carrying the token bridge, with the
 *   route tree beneath it. The frame around each screen is mounted by the route table, not here.
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
    //
    // Trade-offs: centralising every design value in one theme means a screen wanting a one-off value
    //   cannot write it where it is needed -- it has to be added to the bridge in
    //   `ui/src/theme/tokens.ts` and given a name, or done without. That cost is accepted because it
    //   is exactly what makes the zero-hardcoded-values rule mechanically true instead of
    //   aspirational: under CSS-variable theming a literal written into a component does not merely
    //   duplicate a token, it opts that component out of the theme, so a later change to the token
    //   leaves that one component behind and nothing fails. Paying at authoring time converts a
    //   silent divergence into a visible edit in a module that contains no markup.
    //
    // Assumptions: the provider is the OUTERMOST element, and the ordering is load-bearing rather
    //   than stylistic. Every component beneath it resolves its values from the scope this provider
    //   emits, and one that rendered before the scope existed would resolve library defaults instead
    //   -- again a wrong colour rather than an error. Nothing may therefore be hoisted above it, and
    //   since it is the only wrapper here the ordering has exactly one constraint, satisfied by
    //   having a single provider at the top.
    //
    // ⚠️ Refactoring Rationale: the library's own `App` component is now rendered here, and the note
    //   that declined it is withdrawn. That note was accurate about the reason it examined -- nothing
    //   in this tree calls the static `message`, `notification` or `Modal` helpers, and no module
    //   consumes `useApp`, so bridging their context buys nothing -- but the helpers are not the only
    //   thing that wrapper carries, and the other thing was measured missing. It is the only element
    //   the library publishes that establishes a RESET SCOPE: its style rule sets `color`, `fontSize`,
    //   `lineHeight` and `fontFamily` from the theme's own tokens (see the package's `app/style`
    //   module), and without it nothing in the document carries them. A browser measurement of the
    //   running application found exactly that: `document.body` computed `font-family` as
    //   `"Times New Roman"` and `color` as `rgb(0, 0, 0)`, with no element matching `[class*=ant-app]`
    //   anywhere -- so every text node that is not itself an antd component rendered in the user
    //   agent's serif default, beside components rendering in the theme's font. A jsdom probe of the
    //   same tree counted zero such elements before this change.
    //
    //   Assumptions: this also puts the CSS-variable scope on ONE ancestor of everything rather than
    //   on each component separately, which is what lets a non-component element resolve a token at
    //   all. `ui/src/theme/antdTheme.ts` sets `cssVar: { key: 'carddemo' }`, and the library emits the
    //   token layers as `--ant-*` custom properties under the selector `.carddemo`, applying that
    //   class to each component it renders -- 60 elements in the probed tree. Custom properties
    //   inherit, so a scope on this wrapper reaches the whole subtree, where sixty sibling scopes
    //   reach only themselves. A reader measuring `:root` and finding no `--ant-*` properties there is
    //   seeing the keyed scope working as configured rather than a defect: `:root` is where the
    //   library would emit them had the key been left unset, which that module declines for the
    //   reasons it records.
    //
    // Alternatives Considered: importing the library's published `reset.css`, which is the other way
    //   to give the document a reset and is the one a reader familiar with earlier majors reaches for.
    //   Rejected on two grounds. It is a static sheet of literals -- it sets `font-family: sans-serif`
    //   on `html`, among other values -- so it would put typographic values in a second place beside
    //   the token bridge, which is the one thing `ui/index.html` records that it declined a stylesheet
    //   to avoid, and which AAP section 0.4.4 forbids by requiring theming to happen in exactly one
    //   place. And its `html, body { width: 100%; height: 100% }` rules would compete with the mount
    //   point sizing that document owns and that `ui/src/layout/appShell.test.tsx` asserts. The
    //   wrapper below resolves every value it sets from this provider's theme, so it adds no second
    //   source of design values at all.
    //
    // Alternatives Considered: one further application-wide wrapper was evaluated and remains
    //   deliberately absent, named here so its absence reads as a decision.
    //   A top-level error boundary. What it would catch is real -- a render-time throw from a
    //   screen -- and it is still rejected on three grounds. Note that the data router
    //   `ui/src/router.tsx` now builds DOES offer a per-route error element, so this wrapper is not
    //   the only shape available; declining it here declines the outermost one specifically.
    //   It would migrate no reference behaviour: the abend surface is already implemented where the
    //   reference raises it, by the two screens whose programs carry an abend routine, from the
    //   `ABEND-DATA` layout of `app/cpy/CSMSG02Y.cpy` L21-L29. It would render OUTSIDE the frame, so
    //   its fallback would appear with no title band, no message line and no key legend -- less than
    //   the reference showed for the same class of failure. And it would put state into a module whose
    //   stated boundary is that it holds none. A boundary inside the frame's content zone remains
    //   available to whoever needs one; this is not that place.
    //
    // Refactoring Rationale: no global state store appears here, and the omission is a conclusion
    //   rather than an oversight. A reader expecting one is reading the pseudo-conversational
    //   original, where a task ends at every screen turn and all continuity between turns travels in
    //   one passed structure -- `app/cpy/COCOM01Y.cpy` L19-L44. That structure does not survive as a
    //   client-side store; it decomposes four ways, and no way is owned by this file. Its navigation
    //   fields become the browser's own history, owned by `ui/src/router.tsx`. Its identity fields
    //   become claims on a signed token, owned by `ui/src/hooks/useAuth.ts` -- which is what stops a
    //   client asserting its own operator type, as the echoed structure allowed. Its selection fields
    //   become path parameters, so each request is self-describing and independently authorisable.
    //   And its re-entry discriminator disappears outright, because a stateless handler has no
    //   first-entry-versus-re-entry distinction to make. A store here would reassemble the one thing
    //   the migration set out to take apart.
    <ConfigProvider theme={cardDemoTheme}>
      {/*
        Assumptions: this file composes NO frame. The persistent chrome -- the title band, the
        message line and the key legend the baseline showed on every screen -- is
        `ui/src/layout/AppShell.tsx`, mounted once as a layout route in `ui/src/router.tsx`. Mounted
        there it is instantiated once for the whole tree and renders `<Outlet />`, so it persists
        across navigation between children rather than being remounted per screen, and it can frame
        some routes and not others: the bare-origin redirect and the not-found result are
        deliberately outside it. A shell mounted here instead would take the route tree as its
        `children`, and a shell given `children` never reaches its outlet -- so composing one here
        beside the layout route would frame every guarded screen twice, with two banners, two
        contentinfo landmarks and two live regions announcing one message.

        ⚠️ Refactoring Rationale: one remedy for that finding mounted the real shell HERE, as
        the shell element wrapping the router as its `children`, and it is withdrawn in favour of the
        layout routes in `ui/src/router.tsx`. Both make the shell apply, and it accepts either shape -- `children`
        when present, otherwise react-router's `Outlet` -- which is exactly why keeping both was not
        harmless: a shell given `children` never reaches its outlet, so the outer mount rendered the
        router and the inner layout route rendered the matched screen, and every guarded screen was
        framed TWICE. Two `app-shell` regions, two banners, two contentinfo landmarks, two skip links,
        and both shells reading the one publication a screen makes -- so two title bands and two live
        regions announcing one message.

        Assumptions: the layout routes are the mounts that survive, for the reason the route table
        records. There are two of them and they are SIBLINGS -- one holding the public sign-on route,
        one holding the guarded subtree -- so a location matches one branch or the other and exactly one
        shell ever renders; within a branch the shell is instantiated once and renders `<Outlet />`, so
        the frame persists across navigation between that branch's children rather than being remounted
        per screen. A route that one day needs a different frame says so in the table instead of here.
        What this file keeps is the theme, which genuinely is a property of the whole application and of
        no route.

        Trade-offs: a screen therefore cannot replace the frame, which is the intended loss -- the 3270
        original had no such affordance either, and a screen that could would be able to hide which
        transaction an operator was in. What a screen CAN do is delegate zone content to it through
        `useShellSlot`, which is how each screen supplies its own identity, message and key legend
        without composing a second copy of the chrome.
      */}
      {/*
        ⚠️ Refactoring Rationale: the route tree arrives as a ROUTER OBJECT handed to the provider,
        where this file used to render a component that built its own `BrowserRouter` internally. The
        note that stood here argued for that element shape on the ground that two sibling guards
        assert against `ui/src/router.tsx`'s source text, and that argument is withdrawn: it let a
        test's expectations about the shape of a file decide the application's composition, and the
        route specification this migration is held to names the data-router form. Both guards are
        re-anchored -- the shell count on the two sibling layout mounts, the screen census on the route
        array's own delimiters -- so neither loses the defect it was written to catch.

        Assumptions: EXACTLY ONE provider is rendered, and it is rendered here. A second provider over
        the same router object would subscribe twice to one history and render the matched route twice,
        which is the composition-level form of the double-frame defect the route table's two sibling
        shell mounts are shaped to prevent. `ui/src/router.tsx` is the only module that may build a
        router, and this is the only module that may render one.

        Trade-offs: this file still cannot see the routes it renders -- it holds no route constant and
        cannot be read to learn which paths exist -- which is the intended direction of ignorance for a
        composition root. `ROUTE_TABLE` in the route module is the same graph as inert data, so a test
        asserts reachability and access class without mounting React or this provider at all.
      */}
      {/*
        Assumptions: the reset wrapper is given `display: contents`, and that one declaration is what
        makes it safe to add. The element it renders is a `div`, and `ui/index.html` sizes the mount
        point as a flex column one viewport tall so that the frame's row-24 key legend sits at the
        bottom edge exactly as terminal row 24 did. A normally-displayed div between the two would
        become the flex item, the frame inside it would size to its content instead of to the window,
        and the legend would float up under the content -- a measured regression, not a theoretical
        one, since that height chain is asserted by `ui/src/layout/appShell.test.tsx`. With
        `display: contents` the wrapper generates no box at all, so the frame remains the flex item of
        the mount point, while inheritance still flows through the element tree and carries both the
        reset and the `--ant-*` scope into the subtree.

        Trade-offs: `display: contents` is a literal in a tree whose rule is that every value resolves
        to a theme token, and it is accepted for the reason `ui/index.html` accepts its two: it carries
        no design value. It is not a colour, a spacing step, a radius, a typographic value or a motion
        duration, so no token can express it and it competes with nothing in `ui/src/theme/tokens.ts`.
        The alternative -- a `height: 100%` chain through the wrapper -- would have introduced exactly
        the layout literal this avoids, and would have had to be kept in step with the mount point's
        own sizing by hand.

        Assumptions: nothing semantic is lost. `display: contents` once removed elements from the
        accessibility tree in some engines, which is why it is worth naming; the element it applies to
        here is a generic `div` with no role, no name and no landmark, so there is nothing for the
        omission to remove. Every landmark an operator navigates by -- the banner, the main region, the
        contentinfo and the key-legend navigation -- is published by the frame inside it.
      */}
      {/*
        ⚠️ Refactoring Rationale: the reset wrapper above is necessary and was measured NOT to be
        sufficient. `display: contents` generates no box, so the wrapper's own reset rule reaches
        its descendants through inheritance but cannot reach `document.body`, which is its
        ancestor: a browser measurement after that change still found the body computing
        `"Times New Roman"` on a transparent canvas. The component below closes exactly that gap
        and nothing more -- it renders no element, so it cannot re-introduce the height-chain
        regression `display: contents` exists to avoid.
      */}
      <AntApp style={{ display: 'contents' }}>
        <DocumentSurface />
        <RouterProvider router={cardDemoRouter} />
      </AntApp>
    </ConfigProvider>
  );
}
