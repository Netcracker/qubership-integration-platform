/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import React from "react";
import {
  ColumnsFilter,
  getColumnsOrderKey,
  getColumnsVisibleKey,
} from "../../../src/components/table/ColumnsFilter";
import { ACTIONS_COLUMN_KEY } from "../../../src/components/table/actionsColumn";

jest.mock("react-sortablejs", () => ({
  ReactSortable: ({
    children,
    setList,
    list,
  }: {
    children: React.ReactNode;
    setList: (next: { id: string }[]) => void;
    list: { id: string }[];
  }) => (
    <div data-testid="sortable-mock">
      <button
        type="button"
        data-testid="simulate-picker-reorder"
        onClick={() => {
          const ids = list.map((item) => String(item.id));
          setList([...ids].reverse().map((id) => ({ id })));
        }}
      >
        simulate reorder
      </button>
      {children}
    </div>
  ),
}));

const STORAGE_KEY = "columnsFilterTest";

beforeEach(() => {
  localStorage.removeItem(getColumnsOrderKey(STORAGE_KEY));
  localStorage.removeItem(getColumnsVisibleKey(STORAGE_KEY));
});

describe("ColumnsFilter key helpers", () => {
  it("getColumnsOrderKey appends _columnsOrder", () => {
    expect(getColumnsOrderKey("myTable")).toBe("myTable_columnsOrder");
  });

  it("getColumnsVisibleKey appends _columnsVisible", () => {
    expect(getColumnsVisibleKey("myTable")).toBe("myTable_columnsVisible");
  });
});

describe("ColumnsFilter", () => {
  const allColumns = ["name", "protocol", "status", ACTIONS_COLUMN_KEY];
  const defaultColumns = ["name", "protocol", "status"];

  it("does not list the actions key in the picker (excluded from picker keys)", () => {
    const onChange = jest.fn();
    render(
      <ColumnsFilter
        allColumns={allColumns}
        defaultColumns={defaultColumns}
        storageKey={STORAGE_KEY}
        onChange={onChange}
      />,
    );
    expect(screen.queryByRole("checkbox", { name: /actions/i })).toBeNull();
    expect(screen.getByRole("checkbox", { name: /protocol/i })).toBeInTheDocument();
  });

  it("disables the name column checkbox", () => {
    const onChange = jest.fn();
    render(
      <ColumnsFilter
        allColumns={allColumns}
        defaultColumns={defaultColumns}
        storageKey={STORAGE_KEY}
        onChange={onChange}
      />,
    );
    expect(screen.getByRole("checkbox", { name: /^name$/i })).toBeDisabled();
  });

  it("uses labelsByKey for checkbox labels when provided", () => {
    const onChange = jest.fn();
    render(
      <ColumnsFilter
        allColumns={["name", "protocol"]}
        defaultColumns={["name", "protocol"]}
        storageKey={STORAGE_KEY}
        onChange={onChange}
        labelsByKey={{ protocol: "Proto X" }}
      />,
    );
    expect(screen.getByRole("checkbox", { name: "Proto X" })).toBeInTheDocument();
  });

  it("disables checkbox for visibility-locked keys (suffix *)", () => {
    const onChange = jest.fn();
    render(
      <ColumnsFilter
        allColumns={["name", "lockedCol*"]}
        defaultColumns={["name", "lockedCol*"]}
        storageKey={STORAGE_KEY}
        onChange={onChange}
      />,
    );
    expect(screen.getByRole("checkbox", { name: /locked col/i })).toBeDisabled();
  });

  it("updates visibility when a toggleable column is unchecked", async () => {
    const onChange = jest.fn();
    render(
      <ColumnsFilter
        allColumns={allColumns}
        defaultColumns={defaultColumns}
        storageKey={STORAGE_KEY}
        onChange={onChange}
      />,
    );
    const protocol = screen.getByRole("checkbox", { name: /protocol/i });
    expect(protocol).toBeChecked();
    fireEvent.click(protocol);
    await waitFor(() => {
      expect(protocol).not.toBeChecked();
    });
    expect(onChange).toHaveBeenCalled();
    const lastCall =
      onChange.mock.calls[onChange.mock.calls.length - 1] as [
        string[],
        string[],
      ];
    expect(lastCall[1]).not.toContain("protocol");
  });

  it("reset restores order and visibility from props", async () => {
    const onChange = jest.fn();
    localStorage.setItem(
      getColumnsOrderKey(STORAGE_KEY),
      JSON.stringify(["status", "protocol", "name", ACTIONS_COLUMN_KEY]),
    );
    localStorage.setItem(
      getColumnsVisibleKey(STORAGE_KEY),
      JSON.stringify(["name"]),
    );
    render(
      <ColumnsFilter
        allColumns={allColumns}
        defaultColumns={defaultColumns}
        storageKey={STORAGE_KEY}
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /reset/i }));
    await waitFor(() => {
      expect(JSON.parse(localStorage.getItem(getColumnsOrderKey(STORAGE_KEY))!)).toEqual(
        allColumns,
      );
    });
    expect(JSON.parse(localStorage.getItem(getColumnsVisibleKey(STORAGE_KEY))!)).toEqual(
      defaultColumns,
    );
  });

  it("merges picker reorder with excluded tail keys (e.g. actions)", async () => {
    const onChange = jest.fn();
    render(
      <ColumnsFilter
        allColumns={allColumns}
        defaultColumns={defaultColumns}
        storageKey={STORAGE_KEY}
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByTestId("simulate-picker-reorder"));
    await waitFor(() => {
      const stored = JSON.parse(
        localStorage.getItem(getColumnsOrderKey(STORAGE_KEY))!,
      ) as string[];
      expect(stored).toEqual([
        "status",
        "protocol",
        "name",
        ACTIONS_COLUMN_KEY,
      ]);
    });
  });
});
