/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";

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

import { CompactSearch } from "../../../src/components/table/CompactSearch";

describe("CompactSearch", () => {
  it("renders input with value", () => {
    render(
      <CompactSearch
        value="test"
        onChange={jest.fn()}
        placeholder="Search..."
      />,
    );
    expect(screen.getByDisplayValue("test")).toBeInTheDocument();
  });

  it("renders placeholder when value empty", () => {
    render(
      <CompactSearch value="" onChange={jest.fn()} placeholder="Search..." />,
    );
    expect(screen.getByPlaceholderText("Search...")).toBeInTheDocument();
  });

  it("calls onChange when typing", () => {
    const onChange = jest.fn();
    render(
      <CompactSearch value="" onChange={onChange} placeholder="Search..." />,
    );
    const input = screen.getByPlaceholderText("Search...");
    fireEvent.change(input, { target: { value: "abc" } });
    expect(onChange).toHaveBeenCalledWith("abc");
  });

  it("calls onChange on Enter", () => {
    const onChange = jest.fn();
    render(
      <CompactSearch
        value="query"
        onChange={onChange}
        placeholder="Search..."
      />,
    );
    const input = screen.getByDisplayValue("query");
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onChange).toHaveBeenCalledWith("query");
  });

  it("calls onChange on Search button click", () => {
    const onChange = jest.fn();
    render(
      <CompactSearch
        value="query"
        onChange={onChange}
        placeholder="Search..."
      />,
    );
    const button = screen.getByRole("button", { name: /search/i });
    fireEvent.click(button);
    expect(onChange).toHaveBeenCalledWith("query");
  });

  it("calls onChange with empty string when cleared with allowClear", () => {
    const onChange = jest.fn();
    render(
      <CompactSearch
        value="x"
        onChange={onChange}
        placeholder="Search..."
        allowClear
      />,
    );
    const input = screen.getByDisplayValue("x");
    fireEvent.change(input, { target: { value: "" } });
    expect(onChange).toHaveBeenCalledWith("");
  });

  it("calls custom onClear when cleared", () => {
    const onClear = jest.fn();
    const onChange = jest.fn();
    render(
      <CompactSearch
        value="x"
        onChange={onChange}
        onClear={onClear}
        placeholder="Search..."
        allowClear
      />,
    );
    const input = screen.getByDisplayValue("x");
    fireEvent.change(input, { target: { value: "" } });
    expect(onClear).toHaveBeenCalled();
  });

  it("applies style and className to wrapper", () => {
    const { container } = render(
      <CompactSearch
        value=""
        onChange={jest.fn()}
        style={{ width: "300px" }}
        className="my-search"
        placeholder="Search..."
      />,
    );
    const wrapper = container.firstElementChild;
    expect(wrapper).toHaveStyle({ width: "300px" });
    expect(wrapper).toHaveClass("my-search");
  });

  it("Search button has aria-label", () => {
    render(
      <CompactSearch value="" onChange={jest.fn()} placeholder="Search..." />,
    );
    expect(screen.getByRole("button", { name: /search/i })).toBeInTheDocument();
  });

  it("calls onSearchConfirm on Enter when provided", () => {
    const onSearchConfirm = jest.fn();
    const onChange = jest.fn();
    render(
      <CompactSearch
        value="query"
        onChange={onChange}
        onSearchConfirm={onSearchConfirm}
        placeholder="Search..."
      />,
    );
    const input = screen.getByDisplayValue("query");
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onSearchConfirm).toHaveBeenCalledWith("query");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("calls onSearchConfirm on Search button click when provided", () => {
    const onSearchConfirm = jest.fn();
    const onChange = jest.fn();
    render(
      <CompactSearch
        value="query"
        onChange={onChange}
        onSearchConfirm={onSearchConfirm}
        placeholder="Search..."
      />,
    );
    const button = screen.getByRole("button", { name: /search/i });
    fireEvent.click(button);
    expect(onSearchConfirm).toHaveBeenCalledWith("query");
    expect(onChange).not.toHaveBeenCalled();
  });

  it("allowClear=false does not show clear control on empty", () => {
    const onChange = jest.fn();
    render(
      <CompactSearch
        value=""
        onChange={onChange}
        placeholder="Search..."
        allowClear={false}
      />,
    );
    const input = screen.getByPlaceholderText("Search...");
    expect(input).toBeInTheDocument();
    fireEvent.change(input, { target: { value: "a" } });
    expect(onChange).toHaveBeenCalledWith("a");
  });
});
