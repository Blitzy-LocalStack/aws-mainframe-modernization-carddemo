import { theme } from "antd";
import type { ThemeConfig } from "antd";

/**
 * Shared Ant Design theme applied once at the application boundary.
 *
 * Assumptions: CSS-variable mode keeps component values on the design-system
 * token surface instead of duplicating colours, spacing or radii in screen
 * styles. The default algorithm remains the base until the measured BMS token
 * bridge is completed in this module rather than scattered through components.
 */
export const cardDemoTheme: ThemeConfig = {
  algorithm: theme.defaultAlgorithm,
  cssVar: { key: "carddemo" },
  hashed: true,
};
