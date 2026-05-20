/**
 * Shared antd mock: real antd except Table → tests/__mocks__/LightweightTable.
 *
 * Usage (path works from any test file via jest moduleNameMapper):
 *
 * ```ts
 * jest.mock("antd", () =>
 *   require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
 * );
 * ```
 *
 * With extra stubbed exports (e.g. message, Popconfirm):
 *
 * ```ts
 * jest.mock("antd", () => {
 *   const React = require("react");
 *   const { antdMockWithLightweightTable } = require("tests/helpers/antdMockWithLightweightTable");
 *   return antdMockWithLightweightTable({ ... });
 * });
 * ```
 */
import path from "path";

export function antdMockWithLightweightTable(
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  const actual = jest.requireActual("antd") as Record<string, unknown>;
  const mod = jest.requireActual(
    path.join(__dirname, "../__mocks__/LightweightTable"),
  ) as { LightweightTable: unknown };
  return { ...actual, Table: mod.LightweightTable, ...overrides };
}
