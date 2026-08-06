import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

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

createRoot(applicationRoot()).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
