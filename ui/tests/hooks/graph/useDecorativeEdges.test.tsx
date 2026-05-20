/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
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

import { act, renderHook, waitFor } from "@testing-library/react";
import type { Edge } from "@xyflow/react";

import {
  DECORATIVE_PREFIX,
  isDecorativeEdgeId,
  originalEdgeIdFromDecorative,
  useDecorativeEdges,
} from "../../../src/hooks/graph/useDecorativeEdges";
import type { ChainGraphNode } from "../../../src/components/graph/nodes/ChainGraphNodeTypes";

describe("useDecorativeEdges helpers", () => {
  describe("isDecorativeEdgeId", () => {
    it("returns true for prefixed ids", () => {
      expect(isDecorativeEdgeId(`${DECORATIVE_PREFIX}abc`)).toBe(true);
    });
    it("returns false for plain ids", () => {
      expect(isDecorativeEdgeId("abc")).toBe(false);
      expect(isDecorativeEdgeId("")).toBe(false);
    });
  });

  describe("originalEdgeIdFromDecorative", () => {
    it("strips the prefix for decorative ids", () => {
      expect(
        originalEdgeIdFromDecorative(`${DECORATIVE_PREFIX}abc`),
      ).toBe("abc");
    });
    it("returns the id as-is for non-decorative ids", () => {
      expect(originalEdgeIdFromDecorative("abc")).toBe("abc");
    });
  });
});

describe("useDecorativeEdges hook", () => {
  const makeNode = (overrides: Partial<ChainGraphNode>): ChainGraphNode =>
    ({
      id: "n",
      type: "unit",
      position: { x: 0, y: 0 },
      data: {},
      ...overrides,
    }) as unknown as ChainGraphNode;

  const makeEdge = (overrides: Partial<Edge>): Edge =>
    ({
      id: "e",
      source: "s",
      target: "t",
      ...overrides,
    }) as Edge;

  it("returns empty decorative edges when all endpoints are visible", async () => {
    const nodes = [
      makeNode({ id: "a" }),
      makeNode({ id: "b" }),
    ];
    const edges = [makeEdge({ id: "edge-1", source: "a", target: "b" })];

    const { result } = renderHook(() => useDecorativeEdges(nodes, edges));

    await waitFor(() => {
      expect(result.current.decorativeEdges).toEqual([]);
    });
  });

  it("skips edges whose endpoint is missing from the node map", async () => {
    const nodes = [makeNode({ id: "a", hidden: true })];
    const edges = [makeEdge({ source: "a", target: "missing" })];

    const { result } = renderHook(() => useDecorativeEdges(nodes, edges));

    await waitFor(() => {
      expect(result.current.decorativeEdges).toEqual([]);
    });
  });

  it("skips decorative edges whose representative chain is broken", async () => {
    const nodes = [
      makeNode({
        id: "hidden-with-missing-parent",
        hidden: true,
        parentId: "ghost-parent",
      }),
      makeNode({ id: "outside" }),
    ];
    const edges = [
      makeEdge({
        id: "edge-1",
        source: "hidden-with-missing-parent",
        target: "outside",
      }),
    ];

    const { result } = renderHook(() => useDecorativeEdges(nodes, edges));

    await waitFor(() => {
      expect(result.current.decorativeEdges).toEqual([]);
    });
  });

  it("traverses the parent chain until a visible parent is found", async () => {
    const nodes = [
      makeNode({ id: "visible-root" }),
      makeNode({ id: "hidden-mid", hidden: true, parentId: "visible-root" }),
      makeNode({ id: "hidden-leaf", hidden: true, parentId: "hidden-mid" }),
      makeNode({ id: "outside" }),
    ];
    const edges = [
      makeEdge({ id: "edge-1", source: "hidden-leaf", target: "outside" }),
    ];

    const { result } = renderHook(() => useDecorativeEdges(nodes, edges));

    await waitFor(() => {
      expect(result.current.decorativeEdges).toEqual([
        expect.objectContaining({
          id: `${DECORATIVE_PREFIX}edge-1`,
          source: "visible-root",
          target: "outside",
        }),
      ]);
    });
  });

  it("skips decorative edges when representatives collapse to the same node", async () => {
    const nodes = [
      makeNode({ id: "visible-parent" }),
      makeNode({ id: "hidden-a", hidden: true, parentId: "visible-parent" }),
      makeNode({ id: "hidden-b", hidden: true, parentId: "visible-parent" }),
    ];
    const edges = [
      makeEdge({ id: "edge-1", source: "hidden-a", target: "hidden-b" }),
    ];

    const { result } = renderHook(() => useDecorativeEdges(nodes, edges));

    await waitFor(() => {
      expect(result.current.decorativeEdges).toEqual([]);
    });
  });

  it("preserves selected state across rebuilds", async () => {
    const nodes = [
      makeNode({ id: "visible" }),
      makeNode({ id: "hidden", hidden: true, parentId: "visible" }),
      makeNode({ id: "outside" }),
    ];
    const edges = [
      makeEdge({ id: "edge-1", source: "hidden", target: "outside" }),
    ];

    const { result, rerender } = renderHook(
      ({
        currentNodes,
        currentEdges,
      }: {
        currentNodes: ChainGraphNode[];
        currentEdges: Edge[];
      }) => useDecorativeEdges(currentNodes, currentEdges),
      { initialProps: { currentNodes: nodes, currentEdges: edges } },
    );

    await waitFor(() => {
      expect(result.current.decorativeEdges.length).toBe(1);
    });

    act(() => {
      result.current.setDecorativeEdges((prev) =>
        prev.map((e) => ({ ...e, selected: true })),
      );
    });

    rerender({
      currentNodes: [...nodes],
      currentEdges: [...edges],
    });

    await waitFor(() => {
      expect(result.current.decorativeEdges[0]?.selected).toBe(true);
    });
  });
});
