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
 * Assumptions: three things must hold for a successful start, and each has a named failure. The
 * document must carry an element with id `root`, or {@link applicationRoot} throws. The runtime
 * configuration document must either resolve or be absent, the absent case falling back to the
 * build-time variable. And the mount must be the LAST step, because the API client memoises its
 * base URL on first construction. A failure at any of the three mounts nothing and writes the
 * reason into the mount point, which is a deliberate choice recorded at the handler below.
 */

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { loadRuntimeConfig } from './api/runtimeConfig';
import { App } from './App';

/**
 * Resolves the single application mount point declared by `ui/index.html`.
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
 * every action. A missing document is NOT a failure — `loadRuntimeConfig` resolves for that case and
 * the client falls back to its build-time variable, which is what keeps the development server
 * working.
 * @returns {Promise<void>} Resolves once the application is mounted.
 */
async function bootstrap(): Promise<void> {
  await loadRuntimeConfig();
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
   * Reports a start-up failure in the document when no application can be mounted.
   * @param {unknown} reason - Whatever the bootstrap rejected with, which may not be an `Error`.
   * @returns {void} Nothing; the message is written into the mount point directly.
   */
  (reason: unknown) => {
    const detail = reason instanceof Error ? reason.message : String(reason);
    const root = document.getElementById('root');
    if (root !== null) {
      root.textContent = `CardDemo could not start: ${detail}`;
    }
  },
);
