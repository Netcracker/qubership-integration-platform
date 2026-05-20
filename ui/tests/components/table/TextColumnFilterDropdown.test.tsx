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

import {
  TextColumnFilterDropdown,
  getTextColumnFilterFn,
  getTextFilterPredicate,
  getTextListColumnFilterFn,
  isTextFilter,
} from "../../../src/components/table/TextColumnFilterDropdown";

describe("TextColumnFilterDropdown pure helpers", () => {
  describe("isTextFilter", () => {
    it("narrows valid filter objects", () => {
      expect(isTextFilter({ condition: "contains", value: "x" })).toBe(true);
      expect(isTextFilter({ condition: "is" })).toBe(true);
    });

    it("rejects non-objects and invalid shapes", () => {
      expect(isTextFilter(null)).toBe(false);
      expect(isTextFilter("x")).toBe(false);
      expect(isTextFilter({ value: "only" })).toBe(false);
    });
  });

  describe("getTextFilterPredicate", () => {
    const row = (condition: string, value: string, cell: string) =>
      getTextFilterPredicate({
        condition: condition as never,
        value,
      })(cell);

    it("contains / not-contains / starts / ends / is / is-not", () => {
      expect(row("contains", "bc", "AbCd")).toBe(true);
      expect(row("contains", "xx", "abc")).toBe(false);
      expect(row("not-contains", "xx", "abc")).toBe(true);
      expect(row("starts-with", "ab", "abc")).toBe(true);
      expect(row("ends-with", "bc", "abc")).toBe(true);
      expect(row("is", "AbC", "abc")).toBe(true);
      expect(row("is-not", "ab", "abc")).toBe(true);
    });

    it("empty filter value matches any cell", () => {
      expect(row("contains", "", "anything")).toBe(true);
    });
  });

  describe("getTextColumnFilterFn", () => {
    it("filters by key getter and JSON filter", () => {
      const fn = getTextColumnFilterFn<{ name: string }>((r) => r.name);
      const key = JSON.stringify({ condition: "contains", value: "foo" });
      expect(fn(key, { name: "Food" })).toBe(true);
      expect(fn(key, { name: "bar" })).toBe(false);
    });
  });

  describe("getTextListColumnFilterFn", () => {
    it("matches when any key matches predicate", () => {
      const fn = getTextListColumnFilterFn<{ tags: string[] }>((r) => r.tags);
      const key = JSON.stringify({ condition: "is", value: "b" });
      expect(fn(key, { tags: ["a", "b"] })).toBe(true);
      expect(fn(key, { tags: ["c"] })).toBe(false);
    });
  });
});

describe("TextColumnFilterDropdown", () => {
  function renderDropdown(
    props: Partial<React.ComponentProps<typeof TextColumnFilterDropdown>> = {},
  ) {
    const setSelectedKeys = jest.fn();
    const confirm = jest.fn();
    const clearFilters = jest.fn();
    render(
      <TextColumnFilterDropdown
        prefixCls="ant-table-filter"
        visible
        setSelectedKeys={setSelectedKeys}
        selectedKeys={[]}
        confirm={confirm}
        clearFilters={clearFilters}
        {...props}
      />,
    );
    return { setSelectedKeys, confirm, clearFilters };
  }

  it("confirm on Filter button", () => {
    const { confirm } = renderDropdown();
    fireEvent.click(screen.getByRole("button", { name: "Filter" }));
    expect(confirm).toHaveBeenCalled();
  });

  it("Reset clears filters and confirms", () => {
    const { confirm, clearFilters } = renderDropdown();
    fireEvent.click(screen.getByRole("button", { name: "Reset" }));
    expect(clearFilters).toHaveBeenCalled();
    expect(confirm).toHaveBeenCalled();
  });

  it("clearing input calls setSelectedKeys with empty array", () => {
    const setSelectedKeys = jest.fn();
    render(
      <TextColumnFilterDropdown
        prefixCls="ant-table-filter"
        visible
        setSelectedKeys={setSelectedKeys}
        selectedKeys={[JSON.stringify({ condition: "contains", value: "x" })]}
        confirm={jest.fn()}
      />,
    );
    const input = screen.getByRole("textbox");
    fireEvent.change(input, { target: { value: "" } });
    expect(setSelectedKeys).toHaveBeenCalledWith([]);
  });

  it("with enableExact shows Is as default and lists Is not in dropdown", () => {
    renderDropdown({ enableExact: true });
    expect(screen.getByText("Is")).toBeInTheDocument();
    fireEvent.mouseDown(screen.getByRole("combobox"));
    expect(screen.getByText("Is not")).toBeInTheDocument();
  });
});
