/**
 * @jest-environment jsdom
 */

import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { EngineTable } from "../../../../src/components/admin_tools/domains/EngineTable.tsx";
import { RunningStatus, type Engine } from "../../../../src/api/apiTypes.ts";
import { mockDomainsTablesLayoutClasses as layoutStyles } from "../../../helpers/mockDomainsTablesLayout";

jest.mock("react-resizable/css/styles.css", () => ({}));

jest.mock("../../../../src/components/table/ResizableTitle.tsx", () => ({
  ResizableTitle: React.forwardRef<
    HTMLTableCellElement,
    React.ThHTMLAttributes<HTMLTableCellElement> & {
      onResize?: unknown;
      onResizeStop?: unknown;
      width?: unknown;
      minResizeWidth?: unknown;
      maxResizeWidth?: unknown;
      resizeHandleZIndex?: unknown;
    }
  >(function ResizableTitleMock(props, ref) {
    const {
      onResize,
      onResizeStop,
      width,
      minResizeWidth,
      maxResizeWidth,
      resizeHandleZIndex,
      ...rest
    } = props;
    void onResize;
    void onResizeStop;
    void width;
    void minResizeWidth;
    void maxResizeWidth;
    void resizeHandleZIndex;
    return React.createElement("th", { ref, ...rest });
  }),
}));

jest.mock("antd", () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-return -- jest.mock factory
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

jest.mock("antd/lib/table", () => ({}));
jest.mock("antd/es/table/interface", () => ({}));

jest.mock(
  "../../../../src/components/admin_tools/domains/DeploymentsTable.tsx",
  () => ({
    DeploymentsTable: () => <div data-testid="deployments-table-stub" />,
  }),
);

jest.mock(
  "../../../../src/components/admin_tools/domains/hooks/useDeploymentsForEngine.ts",
  () => ({
    useDeploymentsForEngine: () => ({
      deployments: [],
      isLoading: false,
      error: undefined,
      retry: jest.fn(),
    }),
  }),
);

const sampleEngine: Engine = {
  id: "eng-1",
  name: "engine-a",
  host: "10.0.0.1:8080",
  runningStatus: RunningStatus.RUNNING,
  ready: true,
  connected: true,
  namespace: "ns",
};

describe("EngineTable", () => {
  test("uses nested layout classes and wraps deployments with nestedExpandWrap when expanded", () => {
    const { container } = render(
      <EngineTable
        engines={[sampleEngine]}
        isLoading={false}
        domainName="dom-x"
      />,
    );

    const host = container.querySelector(`.${layoutStyles.nestedTableHost}`);
    expect(host).toBeInTheDocument();

    const tableRoot = container.querySelector(".flex-table");
    expect(tableRoot).toHaveClass(layoutStyles.nestedTable);

    fireEvent.click(
      container.querySelector("tbody tr td button[type='button']")!,
    );

    const wrap = document.querySelector(`.${layoutStyles.nestedExpandWrap}`);
    expect(wrap).toBeInTheDocument();
    expect(wrap).toContainElement(screen.getByTestId("deployments-table-stub"));
  });
});
