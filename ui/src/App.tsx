/**
 * @file The application shell: the one place the design-system theme is injected and the one
 * place the persistent frame around every screen is declared.
 *
 * Purpose
 * -------
 * Own the two concerns that are shared by all 21 migrated screens and belong to none of them: the
 * `ConfigProvider` that carries the BMS-to-antd token bridge, and the header/content/footer frame
 * that replaces the fixed 24x80 terminal frame. The route tree itself is `ui/src/router.tsx`'s;
 * this module renders it inside the frame and adds nothing else.
 *
 * Boundary
 * --------
 * Assumptions: this module holds no state, issues no request and knows no route. Anything a screen
 * needs from the shell it takes from the theme through antd's own components, so a screen cannot
 * acquire a dependency on this file and this file cannot acquire one on a screen.
 */

import type { ReactElement } from 'react';

import { ConfigProvider, Flex, Layout, Typography } from 'antd';

import { APP_ORGANISATION_TITLE_DISPLAY, APP_TITLE_DISPLAY } from './messages/messages';
import { CardDemoRouter } from './router';
import { cardDemoTheme } from './theme/antdTheme';

/**
 * Applies the single theme and shared shell around the CardDemo route tree.
 * @returns {ReactElement} The themed application shell.
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
        Assumptions: the shell owns the outlet rather than each screen owning its own frame. Every
        one of the 17 base BMS mapsets carries the same title band and the same trailing legend, so
        the frame is a property of the application and not of a screen; declaring it here means a
        route added later cannot ship without it. Trade-offs: a screen therefore cannot replace the
        header, which is the intended loss -- the 3270 original had no such affordance either, and
        a screen that could would be able to hide which transaction an operator was in.
      */}
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
