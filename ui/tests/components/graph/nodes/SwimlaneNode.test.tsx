/**
 * @jest-environment jsdom
 */
import "@testing-library/jest-dom";
import { expect, it, jest } from "@jest/globals";
import { screen, render } from "@testing-library/react";
import { ContainerNode } from "../../../../src/components/graph/nodes/ContainerNode.tsx";
import { SwimlaneNode } from "../../../../src/components/graph/nodes/SwimlaneNode.tsx";
import { makeProps } from "./node-test.util.ts";

jest.mock("@xyflow/react", () => ({
  Handle: ({ type }: { type: string }) => (
    <div data-testid={`handle-${type}`} />
  ),
  Position: { Left: "left", Right: "right", Top: "top", Bottom: "bottom" },
}));

describe("SwimlaneNode", () => {
  it("should render label text", () => {
    render(<SwimlaneNode {...makeProps({ data: { label: "Node label"} })} />);
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
});
