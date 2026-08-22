/**
 * @file The browser entry point: the one module `ui/index.html` loads, and the only place the
 * application is mounted into the document.
 *
 * Purpose
 * -------
 * Perform the two steps that must happen in a fixed order before any screen renders -- resolve the
 * runtime configuration that tells the API client which gateway to address, then mount
 * {@link App} into the single root element the HTML entry declares. Nothing else belongs here:
 * theming is `ui/src/App.tsx`'s and routing is `ui/src/router.tsx`'s.
 *
 * Startup contract
 * ----------------
 * Assumptions: four things must hold for a successful start, and each has a named failure. The
 * document must carry an element with id `root`, or {@link applicationRoot} throws. The runtime
 * configuration document must either resolve or be absent, the absent case falling back to the
 * build-time variable. The resolved API base URL must be usable, or {@link resolvedApiBaseUrl}
 * throws -- see the call below for why that is checked HERE rather than left to the first request.
 * And the mount must be the LAST step, because the API client memoises its base URL on first
 * construction. A failure at any of the four mounts nothing and reports the reason, which is a
 * deliberate choice recorded at the handler below.
 *
 * Configuration boundary
 * ----------------------
 * Assumptions: configuration reaches this bundle only through `import.meta.env` and through the
 * `config.json` document the deployment publishes beside it. `process.env` is not merely discouraged
 * here but does not compile -- `ui/tsconfig.json` sets `types: []`, so no Node global is in scope for
 * browser code, and `ui/vite.config.ts` sets `envPrefix: 'VITE_'`, which is the mechanical boundary
 * deciding which names are inlined into the bundle when it is built. `ui/.env.example` enumerates
 * those names value-free and there are three of them. This module spells none of them: it asks
 * `ui/src/api/runtimeConfig.ts` for the RESOLVED base URL, which is the module that owns both
 * sources and the precedence between them, so the entry point carries no configuration surface of
 * its own that could drift from that file.
 *
 * What this module deliberately does NOT do
 * -----------------------------------------
 * Refactoring Rationale: the theme is not applied here. There is exactly one `ConfigProvider` in
 * the application and it belongs to {@link App}, which carries the BMS-to-antd token bridge from
 * `ui/src/theme/antdTheme.ts`. Applying tokens here as well would give the tree two theme sources
 * whose disagreement resolves by nesting order rather than by intent, and that is the precise
 * failure the single-injection-point rule exists to prevent. Routing is `ui/src/router.tsx`'s for
 * the same reason: the baseline's `EXEC CICS XCTL` transfer graph becomes one route table, and this
 * module neither declares a route nor chooses between them.
 *
 * Alternatives Considered: no global stylesheet is imported, and none exists to import. antd 6
 * defaults to pure CSS-variables theming and injects what it needs at run time, so unlike its
 * earlier majors it publishes no reset sheet to pull in. Authoring a bespoke one was the
 * alternative and was rejected because it would place design values in a second location beside
 * the theme module, breaking the rule that every value resolves to a token rather than to a
 * literal. The only two things a document owns and a component cannot -- the user-agent margin
 * reset and the viewport sizing of the mount point -- already sit in `ui/index.html`, and neither
 * is a colour, spacing step, radius, typographic value or motion duration, so neither competes
 * with the theme.
 *
 * Assumptions: no `dayjs` locale or plugin is registered here, which is a decision rather than an
 * omission. `dayjs` is a direct dependency because application code formats with it, and antd 6
 * ships its date components already wired to `dayjs`, so the default locale and default plugin set
 * are what every screen is written against. Registration is global and order-dependent, so one
 * that were ever needed would have to run in this module ahead of the first render; adding one
 * speculatively would silently change date rendering across the whole application from a line no
 * screen refers to.
 */

import { StrictMode } from 'react';

// WHY : Assumptions: `createRoot` is the only mounting API available here, not the better of two.
//       React is pinned at 19.2.8 and the legacy `ReactDOM.render` entry point was REMOVED in
//       React 19 rather than deprecated, so the pre-18 form would not resolve at all -- there is no
//       fallback to weigh it against. The concurrent root this returns is also what makes the
//       `StrictMode` double invocation below meaningful, since the pre-18 root never performed it.
import { createRoot } from 'react-dom/client';

import { loadRuntimeConfig, resolvedApiBaseUrl } from './api/runtimeConfig';
import { App } from './App';
import { SHARED_MESSAGES } from './messages/messages';

/**
 * Resolves the single application mount point declared by `ui/index.html`.
 *
 * Assumptions: the id is `root`, and it is a TWO-WAY contract with `ui/index.html`, which declares
 * `<div id="root"></div>` and names this very lookup in the comment above that element. Nothing
 * verifies that the two spellings agree while the bundle is built -- Vite resolves the script path
 * in that document but not the id inside it -- so renaming either side alone is not a build error.
 * It is a page that renders blank with the whole application missing, which is why the contract is
 * written down at both ends rather than at neither.
 *
 * Trade-offs: the absent case throws rather than being asserted away with a non-null `!`. The
 * assertion is shorter and type-checks identically, and it is rejected because it only moves the
 * failure: React would receive `null` as its container and report a type error from inside its own
 * call stack, naming neither this module nor the contract that was broken. Throwing names the
 * broken contract at the point it is detected, which is the difference between a one-line diagnosis
 * and reading a framework stack trace to arrive at the same conclusion.
 * @returns {HTMLElement} The root element used by React.
 * @throws {Error} If the HTML entry contract is broken.
 */
function applicationRoot(): HTMLElement {
  const element = document.getElementById('root');
  if (element === null) {
    throw new Error('CardDemo cannot start because the application root element is missing.');
  }
  return element;
}

/**
 * Loads runtime configuration, then mounts the application.
 *
 * WHY the load precedes the render — Assumptions: the API client captures its base URL the first time
 * it is constructed, and a screen can issue a request during its first effect. Rendering before the
 * configuration is available would let that first request be built against a base URL that had not
 * been resolved yet, and the client would then stay memoized with the wrong value for the life of
 * the tab.
 *
 * Trade-offs: a failed load mounts nothing and reports the reason to the console rather than
 * rendering a partial application. An application whose every request is misaddressed is not usable,
 * so failing visibly at start-up is more useful than a shell that appears to work and then refuses
 * every action. A missing document is NOT by itself a failure — `loadRuntimeConfig` resolves for that
 * case and the build-time variable is used instead, which is what keeps the development server
 * working; what is a failure is neither source producing a usable base URL, which is the state
 * checked below.
 * @returns {Promise<void>} Resolves once the application is mounted.
 * @throws {Error} Asynchronously, by rejecting: from {@link applicationRoot} when the mount point is
 *   absent, from `loadRuntimeConfig` when a configuration document is published but cannot be parsed
 *   or fails validation, and from `resolvedApiBaseUrl` when no source supplied a usable API base URL.
 *   All three are handled by the single rejection handler attached below, which is the only reason
 *   this function does not catch them itself.
 */
async function bootstrap(): Promise<void> {
  await loadRuntimeConfig();

  /*
   * WHY the resolved base URL is validated HERE — Refactoring Rationale: start-up used to await the
   * document and mount whatever came back, including when nothing came back. The image publishes no
   * `VITE_API_BASE_URL` — the bundle is built before any environment exists, which is the whole
   * reason the runtime document exists — so an absent or unreadable document left the resolved value
   * empty and this module mounted an application whose every request threw inside the client factory.
   * What an operator saw was a working sign-on screen answering 'Unable to verify the User ...',
   * which reads as a rejected credential rather than as an unconfigured deployment, and no credential
   * had left the browser at all.
   *
   * Trade-offs: the call's value is DISCARDED, because nothing here needs the URL — the client
   * resolves it again on first construction, through the same function, and memoises it then. What is
   * wanted is the refusal, before anything is mounted, so a misconfigured deployment names itself in
   * one sentence instead of being reported as a screen behaving oddly.
   *
   * Alternatives Considered: letting the first request fail and surfacing a message on the screen.
   * Rejected because every screen would need it, the message would arrive after an operator had
   * already typed a credential into a form that could never submit, and the failure is not a
   * screen's to report: it is the deployment's, and it is fully known before the first paint.
   */
  resolvedApiBaseUrl();

  createRoot(applicationRoot()).render(
    // Trade-offs: StrictMode is kept, and what it costs is a DOUBLE invocation of every component
    //   body, every state initialiser and every effect in a development build -- effects mount,
    //   unmount and mount again. That is paid for one specific class of defect this migration is
    //   exposed to: a screen effect that is not idempotent. Several migrated screens fetch a page
    //   on mount and bind PF7/PF8 to a keyset cursor, so an effect that mutated a cursor instead of
    //   deriving it, or that failed to release a key listener on unmount, would work in a single
    //   invocation and misbehave only under a remount an operator triggered by navigating back --
    //   the hardest kind of failure to reproduce from a bug report. StrictMode turns that into a
    //   duplicated request or a duplicated key handler visible on the first run of the screen.
    //   Assumptions: the cost is development-only. React strips the double invocation from a
    //   production build, so the shipped bundle renders each component once and this wrapper
    //   changes nothing an operator can observe.
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

// WHY : Assumptions: an explicit rejection handler rather than `void`. The lint rule that forbids a
//       floating promise does not accept the void operator here, and requiring the handler is the
//       better outcome: a start-up failure now reports its reason instead of surfacing as an
//       unhandled rejection with no mounted application to show it.
bootstrap().catch(
  /**
   * Reports a start-up failure when no application can be mounted, splitting it across two
   * surfaces: the cause is logged for whoever is diagnosing the deployment, and a fixed
   * operator-facing sentence is written into the mount point. Which detail goes where is the whole
   * point of the handler and is argued at the two statements below.
   * @param {unknown} reason - Whatever the bootstrap rejected with, which may not be an `Error`.
   * @returns {void} Nothing; the sentence is written into the mount point directly.
   */
  (reason: unknown) => {
    // WHY : Refactoring Rationale: the rejection detail goes to the console and NOT to the screen.
    //       This replaces a `CardDemo could not start: <detail>` sentence that was wrong twice over
    //       -- it was invented rather than transcribed, and it published deployment internals to
    //       whoever was looking at the page. Everything this bootstrap rejects with is internal: an
    //       HTTP status the loader could not read past, the fact that the published document was not
    //       JSON, or the API base URL a deployment configured and the shape rule it broke -- and that
    //       last one names an endpoint, which is exactly the kind of value that belongs in a log
    //       rather than on a page. `ui/src/messages/messages.ts` already binds that class of value
    //       with `RedactedDiagnostic` -- where the baseline showed an internal value to the
    //       operator, the target shows a verbatim replacement and the value goes to a log instead
    //       -- and its own register rows resolve to the same replacement used below. The rule is
    //       therefore applied here rather than a second convention invented for the entry point.
    //       Trade-offs: a browser console is not the server-side structured log that discipline
    //       names, because nothing is mounted yet to carry a report to a service. It is where the
    //       detail is recoverable, and logging it is what makes this failure diagnosable at all:
    //       serving the SPA so that `/config.json` answers with HTML rather than JSON produces
    //       exactly this rejection, and with no log the symptom was a blank page and a silent
    //       console that named no cause.
    console.error('carddemo: the application could not be started', reason);

    const root = document.getElementById('root');
    if (root !== null) {
      // WHY : Assumptions: the operator sees a verbatim baseline sentence, transcribed from the
      //       online programs by the message catalog, because every user-visible string in this
      //       migration is carried across character for character rather than paraphrased. This is
      //       the catastrophic-failure wording, which is what a start-up that mounted nothing is.
      //       Trade-offs: it is assigned as `textContent` on the element `ui/index.html` already
      //       provides, rather than rendered as the antd result surface the abend screen uses.
      //       This path runs precisely when React has not mounted, so no component is available to
      //       render and no theme has been applied; assigning text also escapes nothing into
      //       markup, which matters because the sibling branch above declines to place any
      //       server-influenced value on the page.
      root.textContent = SHARED_MESSAGES.UNEXPECTED_ABEND_OCCURRED;
    }
  },
);
