/**
 * @jest-environment jsdom
 */
import React from "react";
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { treeExpandIcon } from "../../../src/components/table/TreeExpandIcon";

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock("../../../src/components/table/TreeExpandIcon.module.css", () => ({
  expandIcon: "expandIcon",
  expandSpacer: "expandSpacer",
}));

describe("treeExpandIcon", () => {
  it("renders chevron button when expandable and calls onExpand", () => {
    const onExpand = jest.fn();
    const Icon = treeExpandIcon<{ id: string }>();
    render(
      <Icon
        expanded={false}
        onExpand={onExpand}
        record={{ id: "r1" }}
        expandable
      />,
    );
    expect(screen.getByTestId("icon-right")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button"));
    expect(onExpand).toHaveBeenCalledWith({ id: "r1" }, expect.any(Object));
  });

  it("shows down icon when expanded", () => {
    const Icon = treeExpandIcon<{ id: string }>();
    render(
      <Icon expanded onExpand={jest.fn()} record={{ id: "r1" }} expandable />,
    );
    expect(screen.getByTestId("icon-down")).toBeInTheDocument();
  });

  it("renders spacer when not expandable", () => {
    const Icon = treeExpandIcon<{ id: string }>();
    const { container } = render(
      <Icon
        expanded={false}
        onExpand={jest.fn()}
        record={{ id: "r1" }}
        expandable={false}
      />,
    );
    expect(container.querySelector(".expandSpacer")).toBeInTheDocument();
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
