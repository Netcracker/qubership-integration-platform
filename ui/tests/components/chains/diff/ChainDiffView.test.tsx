/**
 * @jest-environment jsdom
 */

import { render, screen, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Chain } from "../../../../src/api/apiTypes";
import type { Change } from "../../../../src/components/chains/diff/compare/types";

jest.mock("antd", () => ({
  Flex: jest.fn(({ children }: any) => (
    <div data-testid="flex-container">{children}</div>
  )),
}));

jest.mock(
  "../../../../src/components/chains/diff/ChainDiffViewControls.tsx",
  () => ({
    ChainDiffViewControls: jest.fn(() => <div data-testid="diff-controls" />),
  }),
);

jest.mock(
  "../../../../src/components/chains/diff/ChainDiffGraphView.tsx",
  () => ({
    ChainDiffGraphView: jest.fn(() => <div data-testid="graph-view" />),
  }),
);

jest.mock(
  "../../../../src/components/chains/diff/ChainDiffTableView.tsx",
  () => ({
    ChainDiffTableView: jest.fn(() => <div data-testid="table-view" />),
  }),
);

jest.mock(
  "../../../../src/components/chains/diff/ElementSchemasProvider.tsx",
  () => ({
    ElementSchemasProvider: jest.fn(({ children }: any) => <>{children}</>),
  }),
);

import { Flex } from "antd";
import { ChainDiffViewControls } from "../../../../src/components/chains/diff/ChainDiffViewControls";
import { ChainDiffGraphView } from "../../../../src/components/chains/diff/ChainDiffGraphView";
import { ChainDiffTableView } from "../../../../src/components/chains/diff/ChainDiffTableView";
import { ChainDiffView } from "../../../../src/components/chains/diff/ChainDiffView";

const MockFlex = Flex as jest.Mock;
const MockViewControls = ChainDiffViewControls as jest.Mock;
const MockGraphView = ChainDiffGraphView as jest.Mock;
const MockTableView = ChainDiffTableView as jest.Mock;

const chain1 = { id: "chain-1" } as unknown as Chain;
const chain2 = { id: "chain-2" } as unknown as Chain;
const changes: Change[] = [{ id: "change-1", kind: "element" } as Change];
const onSelectChange = jest.fn();

describe("ChainDiffView", () => {
  it("should render ChainDiffGraphView and not ChainDiffTableView when first mounted", () => {
    render(<ChainDiffView changes={[]} onSelectChange={jest.fn()} />);

    expect(screen.getByTestId("graph-view")).toBeInTheDocument();
    expect(screen.queryByTestId("table-view")).not.toBeInTheDocument();
  });

  it("should render ChainDiffViewControls when mounted", () => {
    render(<ChainDiffView changes={[]} onSelectChange={jest.fn()} />);

    expect(screen.getByTestId("diff-controls")).toBeInTheDocument();
  });

  it("should render ChainDiffTableView and hide ChainDiffGraphView when table view type is selected", () => {
    render(<ChainDiffView changes={[]} onSelectChange={jest.fn()} />);

    const { onViewTypeChange } = MockViewControls.mock.calls[0][0];
    act(() => {
      onViewTypeChange("table");
    });

    expect(screen.getByTestId("table-view")).toBeInTheDocument();
    expect(screen.queryByTestId("graph-view")).not.toBeInTheDocument();
  });

  it("should render ChainDiffGraphView again and hide ChainDiffTableView when graph view type is re-selected after table", () => {
    render(<ChainDiffView changes={[]} onSelectChange={jest.fn()} />);

    const { onViewTypeChange } = MockViewControls.mock.calls[0][0];
    act(() => {
      onViewTypeChange("table");
    });
    act(() => {
      onViewTypeChange("graph");
    });

    expect(screen.getByTestId("graph-view")).toBeInTheDocument();
    expect(screen.queryByTestId("table-view")).not.toBeInTheDocument();
  });

  it("should pass changes, selectedChangeId, and onSelectChange to ChainDiffViewControls when mounted", () => {
    render(
      <ChainDiffView
        changes={changes}
        selectedChangeId="change-1"
        onSelectChange={onSelectChange}
      />,
    );

    const props = MockViewControls.mock.calls[0][0];
    expect(props.changes).toBe(changes);
    expect(props.selectedChangeId).toBe("change-1");
    expect(props.onSelectChange).toBe(onSelectChange);
  });

  it("should pass chain1, chain2, changes, selectedChangeId, and onSelectChange to ChainDiffGraphView when in graph view", () => {
    render(
      <ChainDiffView
        chain1={chain1}
        chain2={chain2}
        changes={changes}
        selectedChangeId="change-1"
        onSelectChange={onSelectChange}
      />,
    );

    const props = MockGraphView.mock.calls[0][0];
    expect(props.chain1).toBe(chain1);
    expect(props.chain2).toBe(chain2);
    expect(props.changes).toBe(changes);
    expect(props.selectedChangeId).toBe("change-1");
    expect(props.onSelectChange).toBe(onSelectChange);
  });

  it("should pass chain1, chain2, changes, selectedChangeId, and onSelectChange to ChainDiffTableView when in table view", () => {
    render(
      <ChainDiffView
        chain1={chain1}
        chain2={chain2}
        changes={changes}
        selectedChangeId="change-1"
        onSelectChange={onSelectChange}
      />,
    );

    const { onViewTypeChange } = MockViewControls.mock.calls[0][0];
    act(() => {
      onViewTypeChange("table");
    });

    const props = MockTableView.mock.calls[0][0];
    expect(props.chain1).toBe(chain1);
    expect(props.chain2).toBe(chain2);
    expect(props.changes).toBe(changes);
    expect(props.selectedChangeId).toBe("change-1");
    expect(props.onSelectChange).toBe(onSelectChange);
  });

  it("should forward extra FlexProps to the outer Flex wrapper when provided", () => {
    render(
      <ChainDiffView
        changes={[]}
        onSelectChange={jest.fn()}
        style={{ height: "100%" }}
      />,
    );

    expect(MockFlex.mock.calls[0][0].style).toEqual({ height: "100%" });
  });
});
