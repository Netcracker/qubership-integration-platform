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
  TimestampColumnFilterDropdown,
  getTimestampColumnFilterFn,
  isTimestampFilter,
} from "../../../src/components/table/TimestampColumnFilterDropdown";

describe("TimestampColumnFilterDropdown helpers", () => {
  describe("isTimestampFilter", () => {
    it("accepts valid timestamp filter objects", () => {
      expect(isTimestampFilter({ condition: "is-before", value: [1] })).toBe(
        true,
      );
      expect(isTimestampFilter({ condition: "is-after" })).toBe(true);
    });

    it("rejects invalid values", () => {
      expect(isTimestampFilter(null)).toBe(false);
      expect(isTimestampFilter({ value: [1] })).toBe(false);
    });
  });

  describe("getTimestampColumnFilterFn", () => {
    const record = (t: number) => ({ t });

    it("is-before", () => {
      const fn = getTimestampColumnFilterFn<{ t: number }>((r) => r.t);
      const key = JSON.stringify({
        condition: "is-before",
        value: [100],
      });
      expect(fn(key, record(50))).toBe(true);
      expect(fn(key, record(150))).toBe(false);
    });

    it("is-after", () => {
      const fn = getTimestampColumnFilterFn<{ t: number }>((r) => r.t);
      const key = JSON.stringify({
        condition: "is-after",
        value: [100],
      });
      expect(fn(key, record(150))).toBe(true);
      expect(fn(key, record(50))).toBe(false);
    });

    it("is-within", () => {
      const fn = getTimestampColumnFilterFn<{ t: number }>((r) => r.t);
      const key = JSON.stringify({
        condition: "is-within",
        value: [100, 200],
      });
      expect(fn(key, record(150))).toBe(true);
      expect(fn(key, record(50))).toBe(false);
      expect(fn(key, record(250))).toBe(false);
    });

    it("empty filter timestamps match any record time", () => {
      const fn = getTimestampColumnFilterFn<{ t: number }>((r) => r.t);
      const before = JSON.stringify({ condition: "is-before", value: [] });
      expect(fn(before, record(999))).toBe(true);
    });
  });
});

describe("TimestampColumnFilterDropdown", () => {
  it("renders single DatePicker when condition is is-before", () => {
    render(
      <TimestampColumnFilterDropdown
        prefixCls="ant-table-filter"
        visible
        setSelectedKeys={jest.fn()}
        selectedKeys={[
          JSON.stringify({
            condition: "is-before",
            value: [Date.now()],
          }),
        ]}
        confirm={jest.fn()}
      />,
    );
    expect(document.querySelector(".ant-picker-range")).toBeNull();
    expect(document.querySelector(".ant-picker")).toBeTruthy();
  });

  it("renders RangePicker when condition is is-within", () => {
    const setSelectedKeys = jest.fn();
    render(
      <TimestampColumnFilterDropdown
        prefixCls="ant-table-filter"
        visible
        setSelectedKeys={setSelectedKeys}
        selectedKeys={[
          JSON.stringify({
            condition: "is-within",
            value: [1, 2],
          }),
        ]}
        confirm={jest.fn()}
      />,
    );
    expect(document.querySelector(".ant-picker-range")).toBeTruthy();
  });

  it("Filter and Reset invoke confirm / clearFilters", () => {
    const confirm = jest.fn();
    const clearFilters = jest.fn();
    render(
      <TimestampColumnFilterDropdown
        prefixCls="ant-table-filter"
        visible
        setSelectedKeys={jest.fn()}
        selectedKeys={[]}
        confirm={confirm}
        clearFilters={clearFilters}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: "Filter" }));
    expect(confirm).toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Reset" }));
    expect(clearFilters).toHaveBeenCalled();
    expect(confirm).toHaveBeenCalledTimes(2);
  });
});
