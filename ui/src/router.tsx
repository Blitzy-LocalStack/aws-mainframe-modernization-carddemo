/**
 * @file The route table: the client-side replacement for the CICS program-transfer graph, and the
 * one place the authentication boundary between screens is drawn.
 *
 * Purpose
 * -------
 * Declare every browser route currently backed by an authored screen module, and decide for each
 * one whether it sits inside or outside the sign-on guard. This is where `EXEC CICS XCTL` ends up:
 * the reference programs transfer control by naming a program in the communication area, and the
 * migrated equivalent is a route change, so the reachability graph of the online transactions is
 * expressed here rather than in any screen.
 *
 * Ownership of navigation
 * -----------------------
 * Assumptions: this module owns the route-to-screen MAPPING and nothing else. It performs no
 * navigation itself -- a screen navigates through `ui/src/routes/navigation.ts`, which validates
 * the destination -- and it holds no route CONSTANT for a card path; those live in
 * `ui/src/routes/cards.ts` beside the selector guards that validate them. Splitting it that way is
 * what lets a screen import a path builder without importing the router.
 *
 * Authentication boundary
 * -----------------------
 * Assumptions: exactly one route is outside the guard, and it is sign-on, because sign-on is the
 * route that establishes the credential the others require. Everything else is wrapped by
 * {@link guarded}, so adding a route means choosing explicitly whether to wrap it. The guard
 * decides what is RENDERED only; every service independently validates the token, so an unwrapped
 * route would expose a screen and no data.
 */

import { Button, Result, Spin } from 'antd';
import { lazy, Suspense } from 'react';
import type { ReactElement } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useNavigate } from 'react-router';

import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from './routes/cards';
import { RequireSignOn, SIGN_ON_ROUTE } from './routes/guards';
import { navigateSafely } from './routes/navigation';
import { SignOnScreen } from './screens/signon';

/*
 * Trade-offs: the three card screens are loaded LAZILY and sign-on is not, and the split is the
 * decision rather than the mechanism. Eager imports would be simpler -- no `lazy`, no `Suspense`,
 * no default-export adapter below -- and would put all four screens plus their antd dependencies
 * into the single chunk the browser must fetch before the sign-on form appears. Sign-on is the only
 * screen an unauthenticated operator can reach, and the three card screens pull the heaviest
 * components in the tree (`Table`, `Descriptions`, `Form` and `Select`), so an eager bundle makes
 * every operator pay for the browse, detail and update screens in order to see a login form. The
 * cost accepted is real and is paid at a different moment: the FIRST navigation to each card route
 * fetches its chunk, so that transition shows the `Suspense` spinner where an eager build would
 * have shown the screen immediately, and a chunk that fails to fetch fails at navigation time
 * rather than at start-up. Assumptions: sign-on is deliberately eager for the same reason -- it is
 * the entry screen, so deferring it would move the spinner to the one place an operator has nothing
 * to look at yet.
 */

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
