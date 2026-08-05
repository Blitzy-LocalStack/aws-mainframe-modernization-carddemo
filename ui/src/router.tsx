import { Button, Result, Spin } from "antd";
import { lazy, Suspense } from "react";
import type { ReactElement } from "react";
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useNavigate,
} from "react-router";

import { CARD_DETAIL_ROUTE, CARD_EDIT_ROUTE } from "./routes/cards";
import { navigateSafely } from "./routes/navigation";

/** Loads the browse screen only when a card route needs it. */
const CardListScreen = lazy(
  /**
   * Imports the browse screen chunk and republishes its named export under the
   * `default` key, which is the only shape React.lazy accepts.
   * @returns {Promise<{ default: () => ReactElement }>} The loaded module,
   *   adapted to the default-export shape.
   */
  async () => {
    const module = await import("./screens/cardList");
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
    const module = await import("./screens/cardDetail");
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
    const module = await import("./screens/cardUpdate");
    return { default: module.CardUpdateScreen };
  },
);

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
              navigateSafely(navigate, "/cards");
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
 * @returns {ReactElement} Browser router with opaque card selectors on every card detail
 *   path.
 */
export function CardDemoRouter(): ReactElement {
  return (
    <BrowserRouter>
      <Suspense fallback={<Spin size="large" />}>
        <Routes>
          <Route path="/" element={<Navigate to="/cards" replace />} />
          <Route path="/cards" element={<CardListScreen />} />
          <Route path={CARD_DETAIL_ROUTE} element={<CardDetailScreen />} />
          <Route path={CARD_EDIT_ROUTE} element={<CardUpdateScreen />} />
          <Route path="*" element={<NotFoundScreen />} />
        </Routes>
      </Suspense>
    </BrowserRouter>
  );
}
