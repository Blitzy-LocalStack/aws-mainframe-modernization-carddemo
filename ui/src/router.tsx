import { Button, Result, Spin } from 'antd';
import { lazy, Suspense } from 'react';
import type { ReactElement } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useNavigate } from 'react-router';

import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from './routes/cards';
import { RequireSignOn, SIGN_ON_ROUTE } from './routes/guards';
import { navigateSafely } from './routes/navigation';
import { SignOnScreen } from './screens/signon';

/** Loads the browse screen only when a card route needs it. */
const CardListScreen = lazy(
  /**
   * Imports the browse screen chunk and republishes its named export under the
   * `default` key, which is the only shape React.lazy accepts.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module,
   *   adapted to the default-export shape.
   */
  async () => {
    const module = await import('./screens/cardList');
    return { default: module.CardListScreen };
  },
);

/** Loads the detail screen only when a card route needs it. */
const CardDetailScreen = lazy(
  /**
   * Imports the detail screen chunk and republishes its named export under the
   * `default` key, which is the only shape React.lazy accepts.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module,
   *   adapted to the default-export shape.
   */
  async () => {
    const module = await import('./screens/cardDetail');
    return { default: module.CardDetailScreen };
  },
);

/** Loads the update screen only when a card route needs it. */
const CardUpdateScreen = lazy(
  /**
   * Imports the update screen chunk and republishes its named export under the
   * `default` key, which is the only shape React.lazy accepts.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module,
   *   adapted to the default-export shape.
   */
  async () => {
    const module = await import('./screens/cardUpdate');
    return { default: module.CardUpdateScreen };
  },
);

/**
 * Wraps one screen element in the authentication guard.
 *
 * Assumptions: a helper rather than repeating the guard element three times. Every card route needs
 * the same protection, and a route added later that forgets it is the failure this collapses into one
 * place — the guard is applied where the route is declared, so a new route cannot be added without
 * choosing whether to wrap it.
 * @param {ReactElement} screen - The screen to protect.
 * @returns {ReactElement} The screen wrapped in the sign-on guard.
 */
function guarded(screen: ReactElement): ReactElement {
  return <RequireSignOn>{screen}</RequireSignOn>;
}

/**
 * Renders a bounded not-found result without reflecting the rejected path.
 * @returns {ReactElement} The not-found result.
 */
function NotFoundScreen(): ReactElement {
  const navigate = useNavigate();
  return (
    <Result
      status="404"
      title="Screen not found"
      subTitle="The requested CardDemo screen is not available."
      extra={
        <Button
          onClick={
            /** Returns the operator to the browse screen from the not-found result. */
            () => {
              navigateSafely(navigate, '/cards');
            }
          }
        >
          Open cards
        </Button>
      }
    />
  );
}

/**
 * Declares the browser routes currently backed by authored screen modules.
 *
 * Assumptions: the sign-on route is the only one outside the guard, because it is the route that
 * establishes the credential the others require. Guarding it would make the application unreachable.
 * @returns {ReactElement} Browser router with opaque card selectors on every card detail
 *   path, and every screen except sign-on behind the authentication guard.
 */
export function CardDemoRouter(): ReactElement {
  return (
    <BrowserRouter>
      <Suspense fallback={<Spin size="large" />}>
        <Routes>
          <Route path="/" element={<Navigate to="/cards" replace />} />
          <Route path={SIGN_ON_ROUTE} element={<SignOnScreen />} />
          <Route path="/cards" element={guarded(<CardListScreen />)} />
          <Route path={CARD_DETAIL_ROUTE} element={guarded(<CardDetailScreen />)} />
          <Route path={CARD_EDIT_ROUTE} element={guarded(<CardUpdateScreen />)} />
          <Route path="*" element={<NotFoundScreen />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}
