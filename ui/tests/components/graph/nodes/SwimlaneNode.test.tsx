/**
 * @jest-environment jsdom
 */
import "@testing-library/jest-dom";
import { expect, it, jest } from "@jest/globals";
import { screen, render } from "@testing-library/react";
import { ContainerNode } from "../../../../src/components/graph/nodes/ContainerNode.tsx";
import { SwimlaneNode } from "../../../../src/components/graph/nodes/SwimlaneNode.tsx";
import { ChainContext } from "../../../../src/pages/ChainPage.tsx";
import { makeProps } from "./node-test.util.ts";
import { Chain } from "../../../../src/api/apiTypes.ts";

jest.mock("@xyflow/react", () => ({
  Handle: ({ type }: { type: string }) => (
    <div data-testid={`handle-${type}`} />
  ),
  Position: { Left: "left", Right: "right", Top: "top", Bottom: "bottom" },
}));

const DEFAULT_SWIMLANE_ID = "swimlane-default";
const REUSE_SWIMLANE_ID = "swimlane-reuse";
const OTHER_SWIMLANE_ID = "swimlane-other";

const mockChain = {
  defaultSwimlaneId: DEFAULT_SWIMLANE_ID,
  reuseSwimlaneId: REUSE_SWIMLANE_ID,
} as Chain;

function renderWithChainContext(
  id: string,
  direction?: "DOWN" | "RIGHT",
  chain = mockChain,
) {
  return render(
    <ChainContext.Provider
      value={{ chain, update: jest.fn(), refresh: jest.fn() }}
    >
      <SwimlaneNode
        {...makeProps({ id, data: { label: "Lane", direction } })}
      />
    </ChainContext.Provider>,
  );
}

describe("SwimlaneNode", () => {
  it("should render label text", () => {
    render(<SwimlaneNode {...makeProps({ data: { label: "Node label" } })} />);
    expect(screen.queryByText("Node label")).toBeInTheDocument();
  });

  it("should trim multiline label to the first line", () => {
    render(
      <ContainerNode {...makeProps({ data: { label: "First\nSecond" } })} />,
    );
    const text = screen.getByText("First").textContent;
    expect(text).toContain("First");
    expect(text).not.toContain("Second");
  });

  describe("swimlane type badge", () => {
    it("should show Default badge for the default swimlane", () => {
      renderWithChainContext(DEFAULT_SWIMLANE_ID);
      expect(screen.getByText("Default")).toBeInTheDocument();
    });

    it("should show Reuse badge for the reuse swimlane", () => {
      renderWithChainContext(REUSE_SWIMLANE_ID);
      expect(screen.getByText("Reuse")).toBeInTheDocument();
    });

    it("should not show any badge for a regular swimlane", () => {
      renderWithChainContext(OTHER_SWIMLANE_ID);
      expect(screen.queryByText("Default")).not.toBeInTheDocument();
      expect(screen.queryByText("Reuse")).not.toBeInTheDocument();
    });

    it("should not show any badge when ChainContext is absent", () => {
      render(
        <SwimlaneNode
          {...makeProps({ id: DEFAULT_SWIMLANE_ID, data: { label: "Lane" } })}
        />,
      );
      expect(screen.queryByText("Default")).not.toBeInTheDocument();
      expect(screen.queryByText("Reuse")).not.toBeInTheDocument();
    });
  });
});
