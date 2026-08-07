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
