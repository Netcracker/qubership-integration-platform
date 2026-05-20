/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Chain } from "../../../../src/api/apiTypes";
import type { Change } from "../../../../src/components/chains/diff/compare/types";

Object.defineProperty(globalThis, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

jest.mock("../../../../src/components/chains/diff/ChangedEntityView.tsx", () => ({
  LinkToChain: ({ chain }: { chain?: any }) =>
    chain ? (
      <a data-testid={`link-chain-${chain.id}`}>{chain.name}</a>
    ) : null,
  ChangedEntityView: jest.fn(
    ({ change, side }: { change: any; side: string }) => (
      <div data-testid={`entity-${side}-${change.id}`} />
    ),
  ),
}));

import { ChainDiffTableView } from "../../../../src/components/chains/diff/ChainDiffTableView";

const chain1 = { id: "chain-1", name: "Alpha" } as unknown as Chain;
const chain2 = { id: "chain-2", name: "Beta" } as unknown as Chain;

const rowCheckbox = (container: HTMLElement, changeId: string) =>
  container
    .querySelector(`[data-row-key="${changeId}"]`)!
    .querySelector('input[type="checkbox"]') as HTMLInputElement;

describe("ChainDiffTableView", () => {
  it.each([
    ["element", "element"],
    ["chain property", "chain-property"],
    ["element property", "element-property"],
    ["connection", "connection"],
  ])(
    'should render "%s" in the Type column when the change kind is "%s"',
    (label, kind) => {
      const change = { id: "c1", kind } as Change;
      render(
        <ChainDiffTableView
          changes={[change]}
          onSelectChange={jest.fn()}
        />,
      );

      expect(screen.getByText(label)).toBeInTheDocument();
    },
  );

  it("should render chain1 name in the first chain column header when chain1 is provided", () => {
    render(
      <ChainDiffTableView
        chain1={chain1}
        changes={[]}
        onSelectChange={jest.fn()}
      />,
    );

    expect(screen.getByTestId("link-chain-chain-1")).toHaveTextContent("Alpha");
  });

  it("should render chain2 name in the second chain column header when chain2 is provided", () => {
    render(
      <ChainDiffTableView
        chain2={chain2}
        changes={[]}
        onSelectChange={jest.fn()}
      />,
    );

    expect(screen.getByTestId("link-chain-chain-2")).toHaveTextContent("Beta");
  });

  it("should render ChangedEntityView for the one side in the chain1 column when chain1 is provided", () => {
    const change = { id: "e1", kind: "element" } as Change;
    render(
      <ChainDiffTableView
        chain1={chain1}
        chain2={chain2}
        changes={[change]}
        onSelectChange={jest.fn()}
      />,
    );

    expect(screen.getByTestId("entity-one-e1")).toBeInTheDocument();
  });

  it("should render ChangedEntityView for the another side in the chain2 column when chain2 is provided", () => {
    const change = { id: "e1", kind: "element" } as Change;
    render(
      <ChainDiffTableView
        chain1={chain1}
        chain2={chain2}
        changes={[change]}
        onSelectChange={jest.fn()}
      />,
    );

    expect(screen.getByTestId("entity-another-e1")).toBeInTheDocument();
  });

  it("should render nothing in the chain1 column when chain1 is not provided", () => {
    const change = { id: "e1", kind: "element" } as Change;
    render(
      <ChainDiffTableView
        chain2={chain2}
        changes={[change]}
        onSelectChange={jest.fn()}
      />,
    );

    expect(screen.queryByTestId("entity-one-e1")).not.toBeInTheDocument();
  });

  it("should render nothing in the chain2 column when chain2 is not provided", () => {
    const change = { id: "e1", kind: "element" } as Change;
    render(
      <ChainDiffTableView
        chain1={chain1}
        changes={[change]}
        onSelectChange={jest.fn()}
      />,
    );

    expect(screen.queryByTestId("entity-another-e1")).not.toBeInTheDocument();
  });

  it("should check the row matching selectedChangeId when selectedChangeId is provided", () => {
    const change = { id: "e1", kind: "element" } as Change;
    const { container } = render(
      <ChainDiffTableView
        changes={[change]}
        selectedChangeId="e1"
        onSelectChange={jest.fn()}
      />,
    );

    expect(rowCheckbox(container, "e1")).toBeChecked();
  });

  it("should not check any row when selectedChangeId is not provided", () => {
    const change = { id: "e1", kind: "element" } as Change;
    const { container } = render(
      <ChainDiffTableView changes={[change]} onSelectChange={jest.fn()} />,
    );

    expect(rowCheckbox(container, "e1")).not.toBeChecked();
  });

  it("should call onSelectChange with the change id when an unchecked row is selected", () => {
    const onSelectChange = jest.fn();
    const change = { id: "e1", kind: "element" } as Change;
    const { container } = render(
      <ChainDiffTableView
        changes={[change]}
        onSelectChange={onSelectChange}
      />,
    );

    fireEvent.click(rowCheckbox(container, "e1"));

    expect(onSelectChange).toHaveBeenCalledWith("e1");
  });

  it("should call onSelectChange with empty string when a selected row is deselected", () => {
    const onSelectChange = jest.fn();
    const change = { id: "e1", kind: "element" } as Change;
    const { container } = render(
      <ChainDiffTableView
        changes={[change]}
        selectedChangeId="e1"
        onSelectChange={onSelectChange}
      />,
    );

    fireEvent.click(rowCheckbox(container, "e1"));

    expect(onSelectChange).toHaveBeenCalledWith("");
  });
});
