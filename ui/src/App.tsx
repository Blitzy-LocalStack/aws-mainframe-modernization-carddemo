import type { ReactElement } from "react";

import { ConfigProvider, Flex, Layout, Typography } from "antd";

import {
  APP_ORGANISATION_TITLE_DISPLAY,
  APP_TITLE_DISPLAY,
} from "./messages/messages";
import { CardDemoRouter } from "./router";
import { cardDemoTheme } from "./theme/antdTheme";

/**
 * Applies the single theme and shared shell around the CardDemo route tree.
 * @returns {ReactElement} The themed application shell.
 */
export function App(): ReactElement {
  return (
    <ConfigProvider theme={cardDemoTheme}>
      <Layout>
        <Layout.Header>
          <Flex align="center" justify="space-between">
            <Typography.Title level={3}>{APP_TITLE_DISPLAY}</Typography.Title>
            <Typography.Text>{APP_ORGANISATION_TITLE_DISPLAY}</Typography.Text>
          </Flex>
        </Layout.Header>
        <Layout.Content>
          <CardDemoRouter />
        </Layout.Content>
        <Layout.Footer>
          <Typography.Text>{APP_TITLE_DISPLAY}</Typography.Text>
        </Layout.Footer>
      </Layout>
    </ConfigProvider>
  );
}
