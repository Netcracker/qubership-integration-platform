import type { CSSProperties, ReactNode } from "react";
import React from "react";

/**
 * Shared antd mock factory for chain tab page tests (Flex + LightweightTable + optional message).
 * From test files use (jest hoists mocks — do not import this module at file top for the factory):
 *
 * `jest.mock("antd", () => require("tests/helpers/chainPageAntdJestMock").createChainPageAntdMock());`
 */
export function createChainPageAntdMock(
  extraOverrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const { antdMockWithLightweightTable } = jest.requireActual<
    typeof import("./antdMockWithLightweightTable")
  >("tests/helpers/antdMockWithLightweightTable");
  const messageApi = {
    info: jest.fn(),
    success: jest.fn(),
    error: jest.fn(),
    warning: jest.fn(),
  };
  return antdMockWithLightweightTable({
    Flex: ({
      children,
      style,
    }: {
      children?: ReactNode;
      style?: CSSProperties;
    }) => React.createElement("div", { style }, children),
    message: {
      useMessage: () => [messageApi, null],
    },
    ...extraOverrides,
  });
}
