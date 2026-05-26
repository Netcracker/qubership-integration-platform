/**
 * @jest-environment jsdom
 */

import { render, screen, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Chain } from "../../../../src/api/apiTypes";
import type { Change } from "../../../../src/components/chains/diff/compare/types";
import type { ComparableItem } from "../../../../src/components/chains/diff/useChainDiff";

const mockUseChainDiff = jest.fn();

jest.mock("../../../../src/components/chains/diff/useChainDiff.tsx", () => ({
  useChainDiff: (...args: unknown[]) => mockUseChainDiff(...args),
}));

jest.mock("antd", () => ({
  Flex: jest.fn(({ children }: any) => (
    <div data-testid="flex-container">{children}</div>
  )),
  Row: jest.fn(({ children }: any) => <div data-testid="row">{children}</div>),
  Col: jest.fn(({ children }: any) => <div>{children}</div>),
  Spin: jest.fn(
    ({ className, size }: { className?: string; size?: string }) => (
      <div
        data-testid="loading-spinner"
        className={className}
        data-size={size}
      />
    ),
  ),
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

jest.mock(
  "../../../../src/components/chains/diff/ComparedItemSelector.tsx",
  () => ({
    ComparedItemSelector: jest.fn(() => (
      <div data-testid="compared-item-selector" />
    )),
  }),
);

import { Flex } from "antd";
import { ChainDiffViewControls } from "../../../../src/components/chains/diff/ChainDiffViewControls";
import { ChainDiffGraphView } from "../../../../src/components/chains/diff/ChainDiffGraphView";
import { ChainDiffTableView } from "../../../../src/components/chains/diff/ChainDiffTableView";
import { ChainDiffView } from "../../../../src/components/chains/diff/ChainDiffView";
import { ComparedItemSelector } from "../../../../src/components/chains/diff/ComparedItemSelector";

const MockFlex = Flex as unknown as jest.Mock;
const MockViewControls = ChainDiffViewControls as jest.Mock;
const MockGraphView = ChainDiffGraphView as jest.Mock;
const MockTableView = ChainDiffTableView as jest.Mock;
const MockComparedItemSelector = ComparedItemSelector as jest.Mock;

const chain1 = { id: "chain-1" } as unknown as Chain;
const chain2 = { id: "chain-2" } as unknown as Chain;
const changes: Change[] = [{ id: "change-1", kind: "element" }];

const item1: ComparableItem = { kind: "snapshot", id: "snap-1" };
const item2: ComparableItem = { kind: "snapshot", id: "snap-2" };

const defaultHookReturn = {
  isLoading: false,
  chain1: undefined as Chain | undefined,
  chain2: undefined as Chain | undefined,
  changes: [] as Change[],
  selectedChangeId: undefined as string | undefined,
  setSelectedChangeId: jest.fn(),
};

describe("ChainDiffView", () => {
  beforeEach(() => {
    mockUseChainDiff.mockReturnValue({
      ...defaultHookReturn,
      setSelectedChangeId: jest.fn(),
    });
  });

  it("should show a loading spinner and hide content when isLoading is true", () => {
    mockUseChainDiff.mockReturnValue({ ...defaultHookReturn, isLoading: true });

    render(<ChainDiffView item1={item1} item2={item2} />);

    expect(screen.getByTestId("loading-spinner")).toBeInTheDocument();
    expect(screen.queryByTestId("graph-view")).not.toBeInTheDocument();
    expect(screen.queryByTestId("diff-controls")).not.toBeInTheDocument();
  });

  it("should hide the spinner and show content when isLoading is false", () => {
    render(<ChainDiffView item1={item1} item2={item2} />);

    expect(screen.queryByTestId("loading-spinner")).not.toBeInTheDocument();
    expect(screen.getByTestId("graph-view")).toBeInTheDocument();
  });

  it("should render ChainDiffGraphView and not ChainDiffTableView when first mounted", () => {
    render(<ChainDiffView item1={item1} item2={item2} />);

    expect(screen.getByTestId("graph-view")).toBeInTheDocument();
    expect(screen.queryByTestId("table-view")).not.toBeInTheDocument();
  });

  it("should render ChainDiffViewControls when mounted", () => {
    render(<ChainDiffView item1={item1} item2={item2} />);

    expect(screen.getByTestId("diff-controls")).toBeInTheDocument();
  });

  it("should render ChainDiffTableView and hide ChainDiffGraphView when table view type is selected", () => {
    render(<ChainDiffView item1={item1} item2={item2} />);

    const { onViewTypeChange } = MockViewControls.mock.calls[0][0];
    act(() => {
      onViewTypeChange("table");
    });

    expect(screen.getByTestId("table-view")).toBeInTheDocument();
    expect(screen.queryByTestId("graph-view")).not.toBeInTheDocument();
  });

  it("should render ChainDiffGraphView again and hide ChainDiffTableView when graph view type is re-selected after table", () => {
    render(<ChainDiffView item1={item1} item2={item2} />);

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

  it("should pass changes and selectedChangeId from the hook to ChainDiffViewControls", () => {
    const mockSetSelectedChangeId = jest.fn();
    mockUseChainDiff.mockReturnValue({
      ...defaultHookReturn,
      changes,
      selectedChangeId: "change-1",
      setSelectedChangeId: mockSetSelectedChangeId,
    });

    render(<ChainDiffView item1={item1} item2={item2} />);

    const props = MockViewControls.mock.calls[0][0];
    expect(props.changes).toBe(changes);
    expect(props.selectedChangeId).toBe("change-1");

    props.onSelectChange("test-id");
    expect(mockSetSelectedChangeId).toHaveBeenCalledWith("test-id");
  });

  it("should pass chain1, chain2, changes, selectedChangeId, and onSelectChange to ChainDiffGraphView when in graph view", () => {
    const mockSetSelectedChangeId = jest.fn();
    mockUseChainDiff.mockReturnValue({
      ...defaultHookReturn,
      chain1,
      chain2,
      changes,
      selectedChangeId: "change-1",
      setSelectedChangeId: mockSetSelectedChangeId,
    });

    render(<ChainDiffView item1={item1} item2={item2} />);

    const props = MockGraphView.mock.calls[0][0];
    expect(props.chain1).toBe(chain1);
    expect(props.chain2).toBe(chain2);
    expect(props.changes).toBe(changes);
    expect(props.selectedChangeId).toBe("change-1");

    props.onSelectChange("test-id");
    expect(mockSetSelectedChangeId).toHaveBeenCalledWith("test-id");
  });

  it("should pass chain1, chain2, changes, selectedChangeId, and onSelectChange to ChainDiffTableView when in table view", () => {
    const mockSetSelectedChangeId = jest.fn();
    mockUseChainDiff.mockReturnValue({
      ...defaultHookReturn,
      chain1,
      chain2,
      changes,
      selectedChangeId: "change-1",
      setSelectedChangeId: mockSetSelectedChangeId,
    });

    render(<ChainDiffView item1={item1} item2={item2} />);

    const { onViewTypeChange } = MockViewControls.mock.calls[0][0];
    act(() => {
      onViewTypeChange("table");
    });

    const props = MockTableView.mock.calls[0][0];
    expect(props.chain1).toBe(chain1);
    expect(props.chain2).toBe(chain2);
    expect(props.changes).toBe(changes);
    expect(props.selectedChangeId).toBe("change-1");

    props.onSelectChange("test-id");
    expect(mockSetSelectedChangeId).toHaveBeenCalledWith("test-id");
  });

  it("should forward extra FlexProps to the outer Flex wrapper when provided", () => {
    render(
      <ChainDiffView item1={item1} item2={item2} style={{ height: "100%" }} />,
    );

    expect(MockFlex.mock.calls[0][0].style).toEqual({ height: "100%" });
  });

  it("should render two ComparedItemSelector components when not loading", () => {
    render(<ChainDiffView item1={item1} item2={item2} />);

    const selectors = screen.getAllByTestId("compared-item-selector");
    expect(selectors).toHaveLength(2);
  });

  it("should pass chain1 and chain2 from the hook to their respective ComparedItemSelector", () => {
    mockUseChainDiff.mockReturnValue({
      ...defaultHookReturn,
      chain1,
      chain2,
    });

    render(<ChainDiffView item1={item1} item2={item2} />);

    expect(MockComparedItemSelector.mock.calls[0][0].chain).toBe(chain1);
    expect(MockComparedItemSelector.mock.calls[1][0].chain).toBe(chain2);
  });

  it("should pass editable1 and editable2 to their respective ComparedItemSelector", () => {
    render(
      <ChainDiffView
        item1={item1}
        item2={item2}
        editable1={true}
        editable2={false}
      />,
    );

    expect(MockComparedItemSelector.mock.calls[0][0].editable).toBe(true);
    expect(MockComparedItemSelector.mock.calls[1][0].editable).toBe(false);
  });

  it("should default editable1 and editable2 to false when not provided", () => {
    render(<ChainDiffView item1={item1} item2={item2} />);

    expect(MockComparedItemSelector.mock.calls[0][0].editable).toBe(false);
    expect(MockComparedItemSelector.mock.calls[1][0].editable).toBe(false);
  });

  it("should pass item1 and item2 to useChainDiff on initial render", () => {
    render(<ChainDiffView item1={item1} item2={item2} />);

    expect(mockUseChainDiff).toHaveBeenCalledWith(item1, item2);
  });
});
