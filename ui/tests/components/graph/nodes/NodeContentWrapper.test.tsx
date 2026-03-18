/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import { NodeContentWrapper } from "../../../../src/components/graph/nodes/NodeContentWrapper.tsx";
import { screen, render } from "@testing-library/react";
import { expect, it } from "@jest/globals";
import { ContainerNode } from "../../../../src/components/graph/nodes/ContainerNode.tsx";
import { makeProps } from "./node-test.util.ts";

jest.mock("@xyflow/react", () => ({
  Handle: ({ type }: { type: string }) => (
    <div data-testid={`handle-${type}`} />
  ),
  Position: { Left: "left", Right: "right", Top: "top", Bottom: "bottom" },
}));

jest.mock(
  "../../../../src/components/graph/nodes/NodeContentWrapper.module.css",
  () => ({
    __esModule: true,
    default: {
      wrapper: "wrapper",
      selected: "selected",
    },
  }),
);

jest.mock("../../../../src/components/graph/nodes/EllipsisLabel.tsx", () => ({
  EllipsisLabel: ({ text }: { text: string }) => <span>{text}</span>,
}));

describe("NodeContentWrapper", () => {
  it("should render context", () => {
    render(<NodeContentWrapper {...makeProps()} >Hello!</NodeContentWrapper>);
    expect(screen.queryByText("Hello!")).toBeInTheDocument();
  });

  it("should not add 'selected' class when not selected", () => {
    const { container } = render(
      <NodeContentWrapper {...makeProps({ selected: false })}>
        Hello!
      </NodeContentWrapper>,
    );
    expect(container.querySelector(".selected")).not.toBeInTheDocument();
  });

  it("should add 'selected' class when selected", () => {
    const { container } = render(
      <NodeContentWrapper {...makeProps({ selected: true })}>
        Hello!
      </NodeContentWrapper>,
    );
    expect(container.querySelector(".selected")).toBeInTheDocument();
  });

  it("should render source handle when inputEnabled", () => {
    const { getByTestId } = render(
      <ContainerNode
        {...makeProps({ data: { inputEnabled: true } })}
      />,
    );
    expect(getByTestId("handle-source")).toBeTruthy();
  });

  it("should render target handle when outputEnabled", () => {
    const { getByTestId } = render(
      <ContainerNode {...makeProps({ data: { outputEnabled: true } })} />,
    );
    expect(getByTestId("handle-target")).toBeTruthy();
  });
});
