/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { render, fireEvent } from "@testing-library/react";
import { ContainerNode } from "../../components/graph/nodes/ContainerNode";
import { Position } from "@xyflow/react";
import type { NodeProps } from "@xyflow/react";
import type { ChainGraphNode } from "../../components/graph/nodes/ChainGraphNodeTypes";

jest.mock("@xyflow/react", () => ({
  Handle: ({ type }: { type: string }) => (
    <div data-testid={`handle-${type}`} />
  ),
  Position: { Left: "left", Right: "right", Top: "top", Bottom: "bottom" },
}));

jest.mock("../../icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

jest.mock("../../components/graph/nodes/ContainerNode.module.css", () => ({
  __esModule: true,
  default: {
    container: "container",
    containerSelected: "containerSelected",
    header: "header",
    actions: "actions",
    labelWrapper: "labelWrapper",
    badge: "badge",
  },
}));

jest.mock("../../components/graph/nodes/EllipsisLabel", () => ({
  EllipsisLabel: ({ text }: { text: string }) => <span>{text}</span>,
}));

function makeProps(
  overrides: Partial<NodeProps<ChainGraphNode>> & {
    data?: Partial<ChainGraphNode["data"]>;
  } = {},
): NodeProps<ChainGraphNode> {
  const { data: dataOverrides, ...rest } = overrides;
  return {
    id: "test-container",
    type: "container",
    data: {
      elementType: "try-catch",
      label: "Container Label",
      description: "",
      properties: {},
      inputEnabled: true,
      outputEnabled: true,
      collapsed: false,
      unitCount: 0,
      onToggleCollapse: jest.fn(),
      ...dataOverrides,
    },
    selected: false,
    isConnectable: true,
    zIndex: 0,
    positionAbsoluteX: 0,
    positionAbsoluteY: 0,
    targetPosition: Position.Left,
    sourcePosition: Position.Right,
    dragging: false,
    dragHandle: undefined,
    parentId: undefined,
    ...rest,
  } as NodeProps<ChainGraphNode>;
}

describe("ContainerNode", () => {
  it("renders label text", () => {
    const { container } = render(<ContainerNode {...makeProps()} />);
    expect(container.textContent).toContain("Container Label");
  });

  it("trims multiline label to first line", () => {
    const { container } = render(
      <ContainerNode
        {...makeProps({ data: { label: "First\nSecond" } })}
      />,
    );
    expect(container.textContent).toContain("First");
    expect(container.textContent).not.toContain("Second");
  });

  it("shows caretDownFilled icon when expanded", () => {
    const { getAllByTestId } = render(
      <ContainerNode {...makeProps({ data: { collapsed: false } })} />,
    );
    const icons = getAllByTestId("icon");
    const caretIcon = icons.find(
      (el) => el.getAttribute("data-icon") === "caretDownFilled",
    );
    expect(caretIcon).toBeTruthy();
  });

  it("shows caretRightFilled icon when collapsed", () => {
    const { getAllByTestId } = render(
      <ContainerNode {...makeProps({ data: { collapsed: true } })} />,
    );
    const icons = getAllByTestId("icon");
    const caretIcon = icons.find(
      (el) => el.getAttribute("data-icon") === "caretRightFilled",
    );
    expect(caretIcon).toBeTruthy();
  });

  it("calls onToggleCollapse when button is clicked", () => {
    const onToggleCollapse = jest.fn();
    const { container } = render(
      <ContainerNode {...makeProps({ data: { onToggleCollapse } })} />,
    );
    const button = container.querySelector("button");
    expect(button).toBeTruthy();
    fireEvent.click(button!);
    expect(onToggleCollapse).toHaveBeenCalledTimes(1);
  });

  it("adds containerSelected class when selected", () => {
    const { container } = render(
      <ContainerNode {...makeProps({ selected: true })} />,
    );
    const rootDiv = container.firstChild as HTMLElement;
    expect(rootDiv.className).toContain("containerSelected");
  });

  it("does not add containerSelected class when not selected", () => {
    const { container } = render(
      <ContainerNode {...makeProps({ selected: false })} />,
    );
    const rootDiv = container.firstChild as HTMLElement;
    expect(rootDiv.className).not.toContain("containerSelected");
  });

  it("renders handles when inputEnabled and outputEnabled", () => {
    const { getByTestId } = render(
      <ContainerNode
        {...makeProps({ data: { inputEnabled: true, outputEnabled: true } })}
      />,
    );
    expect(getByTestId("handle-target")).toBeTruthy();
    expect(getByTestId("handle-source")).toBeTruthy();
  });

  it("hides handles when inputEnabled and outputEnabled are falsy", () => {
    const { queryByTestId } = render(
      <ContainerNode
        {...makeProps({
          data: { inputEnabled: false, outputEnabled: false },
        })}
      />,
    );
    expect(queryByTestId("handle-target")).toBeNull();
    expect(queryByTestId("handle-source")).toBeNull();
  });

  it("has data-node-type=container attribute", () => {
    const { container } = render(<ContainerNode {...makeProps()} />);
    const rootDiv = container.firstChild as HTMLElement;
    expect(rootDiv.getAttribute("data-node-type")).toBe("container");
  });
});
