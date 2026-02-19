/**
 * @jest-environment jsdom
 */
import { describe, it, expect } from "@jest/globals";
import { render } from "@testing-library/react";
import { UnitNode } from "../../components/graph/nodes/UnitNode";
import { Position } from "@xyflow/react";
import type { NodeProps } from "@xyflow/react";
import type { ChainGraphNode } from "../../components/graph/nodes/ChainGraphNodeTypes";

jest.mock("@xyflow/react", () => ({
  Handle: ({
    type,
    position,
  }: {
    type: string;
    position: string;
  }) => <div data-testid={`handle-${type}`} data-position={position} />,
  Position: { Left: "left", Right: "right", Top: "top", Bottom: "bottom" },
}));

jest.mock("../../icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

function makeProps(
  overrides: Partial<NodeProps<ChainGraphNode>> & {
    data?: Partial<ChainGraphNode["data"]>;
  } = {},
): NodeProps<ChainGraphNode> {
  const { data: dataOverrides, ...rest } = overrides;
  return {
    id: "test-node",
    type: "unit",
    data: {
      elementType: "http-trigger",
      label: "Test Label",
      description: "",
      properties: {},
      inputEnabled: true,
      outputEnabled: true,
      typeTitle: "HTTP Trigger",
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

describe("UnitNode", () => {
  it("renders label text", () => {
    const { container } = render(<UnitNode {...makeProps()} />);
    expect(container.textContent).toContain("Test Label");
  });

  it("trims multiline label to first line", () => {
    const { container } = render(
      <UnitNode {...makeProps({ data: { label: "Line1\nLine2\nLine3" } })} />,
    );
    expect(container.textContent).toContain("Line1");
    expect(container.textContent).not.toContain("Line2");
  });

  it("falls back to 'Node' when label is undefined", () => {
    const { container } = render(
      <UnitNode {...makeProps({ data: { label: undefined as unknown as string } })} />,
    );
    expect(container.textContent).toContain("Node");
  });

  it("renders typeTitle badge", () => {
    const { container } = render(<UnitNode {...makeProps()} />);
    expect(container.textContent).toContain("HTTP Trigger");
  });

  it("renders both handles by default", () => {
    const { getByTestId } = render(<UnitNode {...makeProps()} />);
    expect(getByTestId("handle-target")).toBeTruthy();
    expect(getByTestId("handle-source")).toBeTruthy();
  });

  it("hides target handle when inputEnabled is false", () => {
    const { queryByTestId } = render(
      <UnitNode {...makeProps({ data: { inputEnabled: false } })} />,
    );
    expect(queryByTestId("handle-target")).toBeNull();
  });

  it("hides source handle when outputEnabled is false", () => {
    const { queryByTestId } = render(
      <UnitNode {...makeProps({ data: { outputEnabled: false } })} />,
    );
    expect(queryByTestId("handle-source")).toBeNull();
  });

  it("applies error box-shadow when mandatoryChecksPassed is false", () => {
    const { container } = render(
      <UnitNode
        {...makeProps({ data: { mandatoryChecksPassed: false } })}
      />,
    );
    const outerDiv = container.firstChild as HTMLElement;
    expect(outerDiv.style.boxShadow).toContain("var(--ant-color-error");
  });

  it("renders without error when selected", () => {
    const { container } = render(
      <UnitNode {...makeProps({ selected: true })} />,
    );
    expect(container.firstChild).toBeTruthy();
  });
});
