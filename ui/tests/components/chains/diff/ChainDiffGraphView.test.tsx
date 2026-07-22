/**
 * @jest-environment jsdom
 */
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Chain } from "../../../../src/api/apiTypes";
import type { ChainDiffGraphViewProps } from "../../../../src/components/chains/diff/ChainDiffGraphView";

jest.mock("antd", () => ({
  Flex: jest.fn(({ children }: any) => <div>{children}</div>),
  Row: jest.fn(({ children }: any) => <div>{children}</div>),
  Col: jest.fn(({ children }: any) => <div>{children}</div>),
  Tag: jest.fn(({ children }: any) => <span>{children}</span>),
}));

jest.mock("../../../../src/components/chains/diff/ChainGraphPanel.tsx", () => ({
  ChainGraphPanel: jest.fn(() => <div data-testid="chain-graph-panel" />),
}));

jest.mock(
  "../../../../src/components/chains/diff/ChangedEntityView.tsx",
  () => ({
    ChangedEntityView: jest.fn(() => <div data-testid="changed-entity-view" />),
  }),
);

jest.mock(
  "../../../../src/components/chains/diff/DiffDocumentContext.tsx",
  () => ({
    DiffDocumentContextProvider: jest.fn(({ children }: any) => (
      <>{children}</>
    )),
  }),
);

jest.mock(
  "../../../../src/components/chains/diff/ChainGraphChangeProvider.tsx",
  () => ({
    ChainGraphChangeProvider: jest.fn(({ children }: any) => <>{children}</>),
  }),
);

import { ChainDiffGraphView } from "../../../../src/components/chains/diff/ChainDiffGraphView";

const chain1 = {
  id: "chain-1",
  elements: [],
  dependencies: [],
} as unknown as Chain;
const chain2 = {
  id: "chain-2",
  elements: [],
  dependencies: [],
} as unknown as Chain;

function renderView(props: {
  chain1?: Chain;
  chain2?: Chain;
  changes?: ChainDiffGraphViewProps["changes"];
}) {
  return render(<ChainDiffGraphView {...props} onSelectChange={jest.fn()} />);
}

describe("ChainDiffGraphView", () => {
  it("should not render graph panes when changes is undefined", () => {
    renderView({ chain1, chain2, changes: undefined });

    expect(screen.queryAllByTestId("chain-graph-panel")).toHaveLength(0);
  });

  it("should not render graph panes when a chain is missing even though changes are present", () => {
    renderView({ chain1, chain2: undefined, changes: [] });

    expect(screen.queryAllByTestId("chain-graph-panel")).toHaveLength(0);
  });

  it("should render both graph panes when both chains and changes are present", () => {
    renderView({ chain1, chain2, changes: [] });

    expect(screen.getAllByTestId("chain-graph-panel")).toHaveLength(2);
  });
});
