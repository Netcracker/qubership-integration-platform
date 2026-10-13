/**
 * @jest-environment jsdom
 */

import type React from "react";
import { describe, expect, it, jest } from "@jest/globals";
import { act, renderHook } from "@testing-library/react";
import {
  mergeExpandedKeys,
  removeExpandedKeys,
  useTableRowExpandCollapse,
} from "../../../src/components/table/useTableRowExpandCollapse";

type Item = { id: string; children?: Item[] };

const collectIds = (record: Item): React.Key[] => [
  record.id,
  ...(record.children ?? []).flatMap(collectIds),
];

describe("mergeExpandedKeys", () => {
  it("should add subtree ids when expanding while preserving other keys", () => {
    expect(mergeExpandedKeys(["a"], ["b", "c"])).toEqual(["a", "b", "c"]);
  });

  it("should not duplicate ids when subtree is already expanded", () => {
    expect(mergeExpandedKeys(["a", "b"], ["b", "c"])).toEqual(["a", "b", "c"]);
  });

  it("should return empty array when both prev and ids are empty", () => {
    expect(mergeExpandedKeys([], [])).toEqual([]);
  });

  it("should support numeric keys without duplicating them", () => {
    expect(mergeExpandedKeys([1, 2], [2, 3])).toEqual([1, 2, 3]);
  });
});

describe("removeExpandedKeys", () => {
  it("should remove only subtree ids when collapsing", () => {
    expect(removeExpandedKeys(["a", "b", "c"], ["b", "c"])).toEqual(["a"]);
  });

  it("should keep keys unchanged when collapsing an unrelated subtree", () => {
    expect(removeExpandedKeys(["a", "b"], ["x", "y"])).toEqual(["a", "b"]);
  });

  it("should return empty array when collapsing everything", () => {
    expect(removeExpandedKeys(["a", "b"], ["a", "b"])).toEqual([]);
  });

  it("should keep keys unchanged when subtree has no ids", () => {
    expect(removeExpandedKeys(["a", "b"], [])).toEqual(["a", "b"]);
  });
});

describe("useTableRowExpandCollapse", () => {
  it("should expand only the clicked subtree when expandSubtree is called", async () => {
    const { result } = renderHook(() =>
      useTableRowExpandCollapse<Item>({ getSubtreeIds: collectIds }),
    );

    await act(async () => {
      await result.current.expandSubtree({
        id: "b",
        children: [{ id: "c" }],
      });
    });
    expect(result.current.expandedRowKeys).toEqual(["b", "c"]);

    await act(async () => {
      await result.current.expandSubtree({ id: "a" });
    });
    expect(result.current.expandedRowKeys).toEqual(["b", "c", "a"]);
  });

  it("should prefer ids returned by ensureLoaded when they are fresh", async () => {
    const ensureLoaded = jest.fn(
      async (_record: Item): Promise<React.Key[]> => ["b", "c", "d"],
    );
    const { result } = renderHook(() =>
      useTableRowExpandCollapse<Item>({
        getSubtreeIds: () => ["b"],
        ensureLoaded,
      }),
    );

    await act(async () => {
      await result.current.expandSubtree({ id: "b" });
    });
    expect(ensureLoaded).toHaveBeenCalledTimes(1);
    expect(result.current.expandedRowKeys).toEqual(["b", "c", "d"]);
  });

  it("should collapse only the clicked subtree when collapseSubtree is called", async () => {
    const { result } = renderHook(() =>
      useTableRowExpandCollapse<Item>({ getSubtreeIds: collectIds }),
    );

    await act(async () => {
      await result.current.expandSubtree({
        id: "a",
        children: [{ id: "b", children: [{ id: "c" }] }],
      });
      await result.current.expandSubtree({ id: "x" });
    });
    expect(result.current.expandedRowKeys).toEqual(["a", "b", "c", "x"]);

    act(() => {
      result.current.collapseSubtree({
        id: "b",
        children: [{ id: "c" }],
      });
    });
    expect(result.current.expandedRowKeys).toEqual(["a", "x"]);
  });

  it("should keep keys unchanged when subtree has no ids", async () => {
    const { result } = renderHook(() =>
      useTableRowExpandCollapse<Item>({ getSubtreeIds: () => [] }),
    );

    await act(async () => {
      await result.current.expandSubtree({ id: "a" });
    });
    expect(result.current.expandedRowKeys).toEqual([]);

    act(() => {
      result.current.collapseSubtree({ id: "a" });
    });
    expect(result.current.expandedRowKeys).toEqual([]);
  });

  it("should leave keys unchanged when ensureLoaded resolves to no ids", async () => {
    const ensureLoaded = jest.fn(
      async (_record: Item): Promise<React.Key[]> => [],
    );
    const { result } = renderHook(() =>
      useTableRowExpandCollapse<Item>({
        getSubtreeIds: collectIds,
        ensureLoaded,
      }),
    );

    await act(async () => {
      await result.current.expandSubtree({ id: "a" });
    });
    expect(ensureLoaded).toHaveBeenCalledTimes(1);
    expect(result.current.expandedRowKeys).toEqual([]);
  });

  it("should leave keys unchanged and propagate the error when ensureLoaded rejects", async () => {
    const ensureLoaded = jest.fn(
      async (_record: Item): Promise<React.Key[]> => {
        throw new Error("boom");
      },
    );
    const { result } = renderHook(() =>
      useTableRowExpandCollapse<Item>({
        getSubtreeIds: collectIds,
        ensureLoaded,
      }),
    );

    await act(async () => {
      await expect(result.current.expandSubtree({ id: "a" })).rejects.toThrow(
        "boom",
      );
    });
    expect(result.current.expandedRowKeys).toEqual([]);
  });

  it("should keep expanded keys when collapsing an unrelated or empty subtree", async () => {
    const { result } = renderHook(() =>
      useTableRowExpandCollapse<Item>({ getSubtreeIds: collectIds }),
    );

    await act(async () => {
      await result.current.expandSubtree({ id: "a" });
    });
    expect(result.current.expandedRowKeys).toEqual(["a"]);

    act(() => {
      result.current.collapseSubtree({ id: "missing" });
    });
    expect(result.current.expandedRowKeys).toEqual(["a"]);
  });

  it("should keep expanded keys when getSubtreeIds returns no ids", () => {
    const { result } = renderHook(() =>
      useTableRowExpandCollapse<Item>({ getSubtreeIds: () => [] }),
    );

    act(() => {
      result.current.setExpandedRowKeys(["a", "x"]);
    });

    act(() => {
      result.current.collapseSubtree({ id: "a" });
    });
    expect(result.current.expandedRowKeys).toEqual(["a", "x"]);
  });

  it("should apply direct setExpandedRowKeys updates", () => {
    const { result } = renderHook(() =>
      useTableRowExpandCollapse<Item>({ getSubtreeIds: collectIds }),
    );

    act(() => {
      result.current.setExpandedRowKeys(["a", "b"]);
    });
    expect(result.current.expandedRowKeys).toEqual(["a", "b"]);

    act(() => {
      result.current.setExpandedRowKeys([]);
    });
    expect(result.current.expandedRowKeys).toEqual([]);
  });
});
