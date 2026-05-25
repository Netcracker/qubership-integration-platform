/**
 * @jest-environment jsdom
 */

import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { DeploymentsTable } from "../../../../src/components/admin_tools/domains/DeploymentsTable.tsx";
import {
  DeploymentStatus,
  type ChainDeployment,
} from "../../../../src/api/apiTypes.ts";
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
  "../../../../src/components/deployment_runtime_states/DeploymentRuntimeState.tsx",
  () => ({
    DeploymentRuntimeState: () => <span data-testid="deploy-runtime-state" />,
  }),
);

const sampleDeployment: ChainDeployment = {
  id: "dep-1",
  chainId: "chain-uuid",
  chainName: "integration-chain",
  snapshotName: "snap-1",
  state: {
    status: DeploymentStatus.DEPLOYED,
    error: "",
    stacktrace: "",
    suspended: false,
  },
};

describe("DeploymentsTable", () => {
  test("uses nested layout classes for host and table", () => {
    const { container } = render(
      <DeploymentsTable deployments={[sampleDeployment]} isLoading={false} />,
    );

    const host = container.querySelector(`.${layoutStyles.nestedTableHost}`);
    expect(host).toBeInTheDocument();

    const tableRoot = container.querySelector(`.${layoutStyles.nestedTable}`);
    expect(tableRoot).toBeInTheDocument();

    expect(screen.getByText("integration-chain")).toBeInTheDocument();
    expect(screen.getByTestId("deploy-runtime-state")).toBeInTheDocument();
  });
});
