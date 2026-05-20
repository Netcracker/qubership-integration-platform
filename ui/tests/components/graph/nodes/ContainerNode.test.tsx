/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { render, fireEvent } from "@testing-library/react";
import { ContainerNode } from "../../../../src/components/graph/nodes/ContainerNode.tsx";
import { makeProps } from "./node-test.util.ts";

jest.mock("@xyflow/react", () => ({
  Handle: ({ type }: { type: string }) => (
    <div data-testid={`handle-${type}`} />
  ),
  Position: { Left: "left", Right: "right", Top: "top", Bottom: "bottom" },
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

jest.mock(
  "../../../../src/components/graph/nodes/ContainerNode.module.css",
  () => ({
    __esModule: true,
    default: {
      container: "container",
      containerSelected: "containerSelected",
      header: "header",
      actions: "actions",
      labelWrapper: "labelWrapper",
      badge: "badge",
    },
  }),
);

jest.mock("../../../../src/components/graph/nodes/EllipsisLabel.tsx", () => ({
  EllipsisLabel: ({ text }: { text: string }) => <span>{text}</span>,
}));

describe("ContainerNode", () => {
  it("renders label text", () => {
    const { container } = render(<ContainerNode {...makeProps()} />);
    expect(container.textContent).toContain("Container Label");
  });

  it("trims multiline label to first line", () => {
    const { container } = render(
      <ContainerNode {...makeProps({ data: { label: "First\nSecond" } })} />,
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
});
