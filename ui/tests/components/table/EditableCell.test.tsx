/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("../../../src/components/table/EditableCell.module.css", () => ({
  __esModule: true,
  default: {
    editableCellWrapper: "editable-cell-wrapper",
    editingWrapper: "editing-wrapper",
    editingButtons: "editing-buttons",
    inlineIcon: "inline-icon",
  },
}));

import {
  EditableCellTrigger,
  InlineEditWithButtons,
} from "../../../src/components/table/EditableCell";

describe("EditableCellTrigger", () => {
  it("renders with role=button and tabIndex=0", () => {
    render(
      <EditableCellTrigger onClick={jest.fn()}>content</EditableCellTrigger>,
    );
    const el = screen.getByRole("button");
    expect(el).toBeInTheDocument();
    expect(el).toHaveAttribute("tabindex", "0");
  });

  it("calls onClick on click", () => {
    const onClick = jest.fn();
    render(
      <EditableCellTrigger onClick={onClick}>content</EditableCellTrigger>,
    );
    fireEvent.click(screen.getByRole("button"));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("calls onClick on Enter key", () => {
    const onClick = jest.fn();
    render(
      <EditableCellTrigger onClick={onClick}>content</EditableCellTrigger>,
    );
    fireEvent.keyDown(screen.getByRole("button"), { key: "Enter" });
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("calls onClick on Space key", () => {
    const onClick = jest.fn();
    render(
      <EditableCellTrigger onClick={onClick}>content</EditableCellTrigger>,
    );
    fireEvent.keyDown(screen.getByRole("button"), { key: " " });
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("does not call onClick on other keys", () => {
    const onClick = jest.fn();
    render(
      <EditableCellTrigger onClick={onClick}>content</EditableCellTrigger>,
    );
    fireEvent.keyDown(screen.getByRole("button"), { key: "Escape" });
    fireEvent.keyDown(screen.getByRole("button"), { key: "a" });
    expect(onClick).not.toHaveBeenCalled();
  });

  it("applies className to the wrapper", () => {
    render(
      <EditableCellTrigger onClick={jest.fn()} className="my-class">
        content
      </EditableCellTrigger>,
    );
    const el = screen.getByRole("button");
    expect(el.className).toContain("my-class");
  });

  it("applies style to the wrapper", () => {
    render(
      <EditableCellTrigger onClick={jest.fn()} style={{ paddingInlineEnd: 24 }}>
        content
      </EditableCellTrigger>,
    );
    const el = screen.getByRole("button");
    expect(el).toHaveStyle({ paddingInlineEnd: "24px" });
  });

  it("renders children", () => {
    render(
      <EditableCellTrigger onClick={jest.fn()}>
        <span>child text</span>
      </EditableCellTrigger>,
    );
    expect(screen.getByText("child text")).toBeInTheDocument();
  });
});

describe("InlineEditWithButtons", () => {
  it("renders children", () => {
    render(
      <InlineEditWithButtons onApply={jest.fn()} onCancel={jest.fn()}>
        <input data-testid="input" />
      </InlineEditWithButtons>,
    );
    expect(screen.getByTestId("input")).toBeInTheDocument();
  });

  it("calls onApply when Apply button is clicked", () => {
    const onApply = jest.fn();
    render(
      <InlineEditWithButtons onApply={onApply} onCancel={jest.fn()}>
        <span>content</span>
      </InlineEditWithButtons>,
    );
    fireEvent.click(screen.getByRole("button", { name: /apply/i }));
    expect(onApply).toHaveBeenCalledTimes(1);
  });

  it("calls onCancel when Cancel button is clicked", () => {
    const onCancel = jest.fn();
    render(
      <InlineEditWithButtons onApply={jest.fn()} onCancel={onCancel}>
        <span>content</span>
      </InlineEditWithButtons>,
    );
    fireEvent.click(screen.getByRole("button", { name: /cancel/i }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("shows both buttons by default (showButtons=true)", () => {
    render(
      <InlineEditWithButtons onApply={jest.fn()} onCancel={jest.fn()}>
        <span>content</span>
      </InlineEditWithButtons>,
    );
    expect(screen.getAllByRole("button")).toHaveLength(2);
  });

  it("hides buttons when showButtons=false", () => {
    render(
      <InlineEditWithButtons
        onApply={jest.fn()}
        onCancel={jest.fn()}
        showButtons={false}
      >
        <span>content</span>
      </InlineEditWithButtons>,
    );
    expect(screen.queryAllByRole("button")).toHaveLength(0);
  });

  it("proxies onKeyDown to the child", () => {
    const onKeyDown = jest.fn();
    render(
      <InlineEditWithButtons
        onApply={jest.fn()}
        onCancel={jest.fn()}
        onKeyDown={onKeyDown}
      >
        <span>content</span>
      </InlineEditWithButtons>,
    );
    fireEvent.keyDown(screen.getByText("content"), { key: "Escape" });
    expect(onKeyDown).toHaveBeenCalledTimes(1);
  });

  it("renders with role=group for wrapper", () => {
    render(
      <InlineEditWithButtons onApply={jest.fn()} onCancel={jest.fn()}>
        <span>content</span>
      </InlineEditWithButtons>,
    );
    expect(screen.getByRole("group")).toBeInTheDocument();
  });
});
