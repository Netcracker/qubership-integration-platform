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

import { renderHook } from "@testing-library/react";
import { Position, useReactFlow } from "@xyflow/react";
import type { Edge, Node } from "@xyflow/react";
import type { ElkNode } from "elkjs/lib/elk.bundled";
import { arrangeNodes, useAutoLayout } from "../../../src/hooks/graph/useAutoLayout.tsx";
import { useElkDirection } from "../../../src/hooks/graph/useElkDirection.tsx";

jest.mock("@xyflow/react", () => ({
  Position: {
    Left: "left",
    Right: "right",
    Top: "top",
    Bottom: "bottom",
  },
  useReactFlow: jest.fn(),
}));

const mockElkLayout = jest.fn();

jest.mock("../../../src/hooks/graph/useElkDirection.tsx", () => ({
  useElkDirection: jest.fn(),
}));

jest.mock("elkjs/lib/elk.bundled", () => ({
  __esModule: true,
  default: jest.fn().mockImplementation(() => ({
    layout: (...args: unknown[]) => mockElkLayout(...args),
  })),
}));

type TestNodeData = Record<string, unknown>;

type LayoutOverride = {
  x?: number;
  y?: number;
  width?: number;
  height?: number;
};

const makeNode = (
  id: string,
  override: Partial<Node<TestNodeData>> = {},
): Node<TestNodeData> => ({
  id,
  type: "unit",
  position: { x: 0, y: 0 },
  data: {},
  width: 100,
  height: 50,
  ...override,
});

const makeEdge = (id: string, source: string, target: string): Edge => ({
  id,
  source,
  target,
});

const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value));

describe("useAutoLayout", () => {
  const layoutMock = mockElkLayout;
  const toggleDirection = jest.fn();

  const getNodes = jest.fn();
  const getEdges = jest.fn();
  const getNodesBounds = jest.fn();
  const updateNode = jest.fn();
  const fitView = jest.fn();

  let layoutById: Map<string, LayoutOverride>;

  beforeEach(() => {
    jest.clearAllMocks();

    layoutById = new Map();

    global.requestAnimationFrame = jest.fn(
      (callback: FrameRequestCallback): number => {
        callback(0);
        return 1;
      },
    );

    (useElkDirection as jest.Mock).mockReturnValue({
      direction: "RIGHT",
      toggleDirection,
    });

    (useReactFlow as jest.Mock).mockReturnValue({
      getNodes,
      getEdges,
      getNodesBounds,
      updateNode,
      fitView,
    });

    getNodes.mockReturnValue([]);
    getEdges.mockReturnValue([]);

    getNodesBounds.mockImplementation(([node]: Node[]) => ({
      x: node.position.x,
      y: node.position.y,
      width: node.width ?? 150,
      height: node.height ?? 50,
    }));

    layoutMock.mockImplementation(async (graph: ElkNode) => {
      const nextGraph = clone(graph);

      const applyLayout = (node: ElkNode) => {
        const override = layoutById.get(node.id) ?? {};

        node.x = override.x ?? node.x ?? 0;
        node.y = override.y ?? node.y ?? 0;
        node.width = override.width ?? node.width ?? 150;
        node.height = override.height ?? node.height ?? 50;

        node.children?.forEach(applyLayout);
      };

      nextGraph.children?.forEach(applyLayout);

      return nextGraph;
    });
  });

  it("shouldBuildNestedElkGraphAndMapLayoutResultWhenDirectionIsRight", async () => {
    layoutById.set("container", { x: 10, y: 20, width: 300, height: 180 });
    layoutById.set("source", { x: 30, y: 40, width: 100, height: 50 });
    layoutById.set("target", { x: 180, y: 40, width: 100, height: 50 });

    const nodes: Node<TestNodeData>[] = [
      makeNode("container", {
        type: "container",
        width: 240,
        height: 120,
      }),
      makeNode("source", {
        parentId: "container",
      }),
      makeNode("target", {
        parentId: "container",
      }),
    ];

    const edges = [makeEdge("edge-source-target", "source", "target")];

    const result = await arrangeNodes(nodes, edges, "RIGHT");

    const elkGraph = layoutMock.mock.calls[0][0] as ElkNode;
    const containerGraphNode = elkGraph.children?.find(
      (node) => node.id === "container",
    );

    expect(elkGraph.layoutOptions).toMatchObject({
      "elk.direction": "RIGHT",
      "elk.alignment": "LEFT",
    });

    expect(containerGraphNode?.children?.map((node) => node.id)).toEqual([
      "source",
      "target",
    ]);

    expect(containerGraphNode?.edges).toEqual([
      {
        id: "edge-source-target",
        sources: ["source"],
        targets: ["target"],
      },
    ]);

    expect(result).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          id: "container",
          position: { x: 10, y: 20 },
          width: 300,
          height: 180,
          targetPosition: Position.Left,
          sourcePosition: Position.Right,
          data: expect.objectContaining({ direction: "RIGHT" }),
        }),
        expect.objectContaining({
          id: "source",
          parentId: "container",
          position: { x: 30, y: 40 },
          width: 100,
          height: 50,
          targetPosition: Position.Left,
          sourcePosition: Position.Right,
          data: expect.objectContaining({ direction: "RIGHT" }),
        }),
        expect.objectContaining({
          id: "target",
          parentId: "container",
          position: { x: 180, y: 40 },
          width: 100,
          height: 50,
        }),
      ]),
    );
  });

  it("shouldFilterHiddenNodesAndEdgesFromElkGraphAndKeepHiddenNodeUntouched", async () => {
    layoutById.set("visible", { x: 10, y: 15, width: 100, height: 50 });

    const hiddenNode = makeNode("hidden", {
      hidden: true,
      position: { x: 999, y: 888 },
      width: 77,
      height: 66,
    });

    const nodes: Node<TestNodeData>[] = [makeNode("visible"), hiddenNode];

    const edges = [
      makeEdge("visible-to-hidden", "visible", "hidden"),
      makeEdge("hidden-to-visible", "hidden", "visible"),
    ];

    const result = await arrangeNodes(nodes, edges, "RIGHT");

    const elkGraph = layoutMock.mock.calls[0][0] as ElkNode;

    expect(elkGraph.children?.map((node) => node.id)).toEqual(["visible"]);
    expect(elkGraph.edges).toEqual([]);

    expect(result.find((node) => node.id === "visible")).toMatchObject({
      position: { x: 10, y: 15 },
      width: 100,
      height: 50,
    });

    expect(result.find((node) => node.id === "hidden")).toMatchObject({
      id: "hidden",
      hidden: true,
      position: { x: 999, y: 888 },
      width: 77,
      height: 66,
    });
  });

  it("shouldLeftAlignDisconnectedSiblingsWithoutOverlappingWhenDirectionIsRight", async () => {
    layoutById.set("container", { x: 0, y: 0, width: 500, height: 200 });
    layoutById.set("first", { x: 100, y: 0, width: 50, height: 50 });
    layoutById.set("second", { x: 200, y: 10, width: 50, height: 50 });

    const nodes: Node<TestNodeData>[] = [
      makeNode("container", {
        type: "container",
        width: 500,
        height: 200,
      }),
      makeNode("first", {
        parentId: "container",
        width: 50,
        height: 50,
      }),
      makeNode("second", {
        parentId: "container",
        width: 50,
        height: 50,
      }),
    ];

    const result = await arrangeNodes(nodes, [], "RIGHT");

    expect(result.find((node) => node.id === "first")?.position).toEqual({
      x: 100,
      y: 0,
    });

    expect(result.find((node) => node.id === "second")?.position).toEqual({
      x: 170,
      y: 10,
    });
  });

  it("shouldEqualizeSwimlaneHeightsWhenDirectionIsDown", async () => {
    layoutById.set("swimlane-1", { x: 0, y: 0, width: 200, height: 120 });
    layoutById.set("swimlane-2", { x: 0, y: 200, width: 220, height: 300 });

    const nodes: Node<TestNodeData>[] = [
      makeNode("swimlane-1", {
        type: "swimlane",
        width: 200,
        height: 120,
      }),
      makeNode("swimlane-2", {
        type: "swimlane",
        width: 220,
        height: 300,
      }),
    ];

    const result = await arrangeNodes(nodes, [], "DOWN");

    expect(result.find((node) => node.id === "swimlane-1")?.height).toBe(300);
    expect(result.find((node) => node.id === "swimlane-2")?.height).toBe(300);

    expect(result.find((node) => node.id === "swimlane-1")?.targetPosition).toBe(
      Position.Top,
    );
    expect(result.find((node) => node.id === "swimlane-1")?.sourcePosition).toBe(
      Position.Bottom,
    );
  });

  it("shouldReturnDirectionToggleDirectionAndArrangeNodesSortedTopologically", async () => {
    const { result } = renderHook(() => useAutoLayout());

    expect(layoutMock).not.toHaveBeenCalled();

    layoutMock.mockClear();

    layoutById.set("parent", { x: 0, y: 0, width: 300, height: 200 });
    layoutById.set("child", { x: 20, y: 30, width: 100, height: 50 });

    const nodes: Node<TestNodeData>[] = [
      makeNode("child", {
        parentId: "parent",
      }),
      makeNode("parent", {
        type: "container",
      }),
    ];

    const arranged = await result.current.arrangeNodes(nodes, []);

    const elkGraph = layoutMock.mock.calls[0][0] as ElkNode;

    expect(result.current.direction).toBe("RIGHT");
    expect(result.current.toggleDirection).toBe(toggleDirection);

    expect(elkGraph.children?.map((node) => node.id)).toEqual(["parent"]);
    expect(elkGraph.children?.[0]?.children?.map((node) => node.id)).toEqual([
      "child",
    ]);

    expect(arranged.map((node) => node.id)).toEqual(["parent", "child"]);
  });

  it("shouldNotRunAutoLayoutOnMount", () => {
    layoutById.set("node-1", { x: 10, y: 20, width: 111, height: 55 });
    layoutById.set("node-2", { x: 200, y: 20, width: 222, height: 66 });

    getNodes.mockReturnValue([
      makeNode("node-1", {
        width: 10,
        height: 10,
      }),
      makeNode("node-2", {
        width: 20,
        height: 20,
      }),
    ]);

    getEdges.mockReturnValue([]);

    getNodesBounds.mockImplementation(([node]: Node[]) => ({
      x: node.position.x,
      y: node.position.y,
      width: node.id === "node-1" ? 111 : 222,
      height: node.id === "node-1" ? 55 : 66,
    }));

    renderHook(() => useAutoLayout());

    expect(layoutMock).not.toHaveBeenCalled();
    expect(updateNode).not.toHaveBeenCalled();
    expect(fitView).not.toHaveBeenCalled();
  });
});
