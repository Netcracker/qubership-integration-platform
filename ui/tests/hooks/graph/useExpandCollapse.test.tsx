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

import { beforeEach, describe, expect, it, jest } from "@jest/globals";
import { act, renderHook } from "@testing-library/react";
import type { Edge } from "@xyflow/react";

import { useExpandCollapse } from "../../../src/hooks/graph/useExpandCollapse";
import type { ChainGraphNode } from "../../../src/components/graph/nodes/ChainGraphNodeTypes";
import { computeNestedUnitCounts } from "../../../src/misc/chain-graph-utils.ts";

jest.mock("../../../src/misc/chain-graph-utils.ts", () => ({
  computeNestedUnitCounts: jest.fn(),
}));

const mockedComputeNestedUnitCounts =
  computeNestedUnitCounts as jest.MockedFunction<
    typeof computeNestedUnitCounts
  >;

function createNode(
  overrides: Partial<ChainGraphNode> & { data?: Record<string, unknown> } = {},
): ChainGraphNode {
  const { data, ...rest } = overrides as any;

  return {
    id: "node",
    type: "unit",
    position: { x: 0, y: 0 },
    data: data ?? {},
    ...rest,
  } as ChainGraphNode;
}

function createContainer(
  overrides: Partial<ChainGraphNode> & { data?: Record<string, unknown> } = {},
): ChainGraphNode {
  const { data, ...rest } = overrides as any;

  return {
    id: "container",
    type: "container",
    position: { x: 0, y: 0 },
    data: {
      elementType: "container",
      collapsed: false,
      ...data,
    },
    ...rest,
  } as ChainGraphNode;
}

function createEdge(overrides: Partial<Edge> = {}): Edge {
  return {
    id: "edge",
    source: "source",
    target: "target",
    ...overrides,
  };
}

describe("useExpandCollapse", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedComputeNestedUnitCounts.mockReturnValue(new Map());
  });

  it("should collapse container, hide nested nodes and hide connected edges on toggle", () => {
    mockedComputeNestedUnitCounts.mockReturnValue(new Map([["root", 1]]));

    const nodes = [
      createContainer({
        id: "root",
        data: { collapsed: false, elementType: "container" },
      }),
      createNode({
        id: "child",
        parentId: "root",
        selected: true,
      }),
      createNode({
        id: "outside",
      }),
    ];

    const edges = [
      createEdge({
        id: "edge-1",
        source: "outside",
        target: "child",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    act(() => {
      result.current.toggle("root");
    });

    expect(setNodes).toHaveBeenCalledTimes(1);
    expect(setEdges).toHaveBeenCalledTimes(1);
    expect(structureChanged).toHaveBeenCalledWith(["root"]);

    const processedNodes = setNodes.mock.calls[0][0] as ChainGraphNode[];
    const processedEdges = setEdges.mock.calls[0][0] as Edge[];

    const root = processedNodes.find((node) => node.id === "root");
    const child = processedNodes.find((node) => node.id === "child");

    expect(root?.data?.collapsed).toBe(true);
    expect(root?.data?.inputEnabled).toBe(true);
    expect(root?.data?.outputEnabled).toBe(true);
    expect(root?.connectable).toBe(false);
    expect(root?.data?.unitCount).toBe(1);

    expect(child?.hidden).toBe(true);
    expect(child?.selected).toBe(false);

    expect(processedEdges[0].hidden).toBe(true);
  });

  it("should call structureChanged with parent id when toggling nested container", () => {
    const nodes = [
      createContainer({
        id: "parent",
        data: { collapsed: false, elementType: "container" },
      }),
      createContainer({
        id: "nested",
        parentId: "parent",
        data: { collapsed: false, elementType: "container" },
      }),
      createNode({
        id: "child",
        parentId: "nested",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    act(() => {
      result.current.toggle("nested");
    });

    expect(structureChanged).toHaveBeenCalledWith(["parent"]);
  });

  it("should expand only requested containers in expandContainers", () => {
    mockedComputeNestedUnitCounts.mockReturnValue(
      new Map([
        ["c1", 1],
        ["c2", 1],
      ]),
    );

    const nodes = [
      createContainer({
        id: "c1",
        data: { collapsed: true, elementType: "container" },
      }),
      createContainer({
        id: "c2",
        data: { collapsed: true, elementType: "container" },
      }),
      createNode({
        id: "child-1",
        parentId: "c1",
        selected: true,
      }),
      createNode({
        id: "child-2",
        parentId: "c2",
        selected: true,
      }),
    ];

    const edges = [
      createEdge({
        id: "edge-1",
        source: "child-1",
        target: "child-2",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    act(() => {
      result.current.expandContainers(["c1", "c1", ""]);
    });

    expect(setNodes).toHaveBeenCalledTimes(1);
    expect(setEdges).toHaveBeenCalledTimes(1);
    expect(structureChanged).toHaveBeenCalledWith(["c1"]);

    const processedNodes = setNodes.mock.calls[0][0] as ChainGraphNode[];

    const c1 = processedNodes.find((node) => node.id === "c1");
    const c2 = processedNodes.find((node) => node.id === "c2");
    const child1 = processedNodes.find((node) => node.id === "child-1");
    const child2 = processedNodes.find((node) => node.id === "child-2");

    expect(c1?.data?.collapsed).toBe(false);
    expect(c2?.data?.collapsed).toBe(true);
    expect(child1?.hidden).toBe(false);
    expect(child2?.hidden).toBe(true);
    expect(child2?.selected).toBe(false);
  });

  it("should do nothing in expandContainers when ids list is empty", () => {
    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse([], setNodes, [], setEdges, structureChanged),
    );

    act(() => {
      result.current.expandContainers([]);
    });

    expect(setNodes).not.toHaveBeenCalled();
    expect(setEdges).not.toHaveBeenCalled();
    expect(structureChanged).not.toHaveBeenCalled();
  });

  it("should expand all containers including nested ones in expandAllContainers", () => {
    mockedComputeNestedUnitCounts.mockReturnValue(
      new Map([
        ["root", 2],
        ["nested", 1],
      ]),
    );

    const nodes = [
      createContainer({
        id: "root",
        data: { collapsed: true, elementType: "container" },
      }),
      createContainer({
        id: "nested",
        parentId: "root",
        data: { collapsed: true, elementType: "container" },
      }),
      createNode({
        id: "child",
        parentId: "nested",
        hidden: true,
        selected: true,
      }),
      createNode({
        id: "outside",
      }),
    ];

    const edges = [
      createEdge({
        id: "edge-1",
        source: "outside",
        target: "child",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    act(() => {
      result.current.expandAllContainers();
    });

    expect(setNodes).toHaveBeenCalledTimes(1);
    expect(setEdges).toHaveBeenCalledTimes(1);
    expect(structureChanged).toHaveBeenCalledWith(["root", "nested"]);

    const processedNodes = setNodes.mock.calls[0][0] as ChainGraphNode[];
    const processedEdges = setEdges.mock.calls[0][0] as Edge[];

    expect(
      processedNodes.find((node) => node.id === "root")?.data?.collapsed,
    ).toBe(false);
    expect(
      processedNodes.find((node) => node.id === "nested")?.data?.collapsed,
    ).toBe(false);
    expect(processedNodes.find((node) => node.id === "child")?.hidden).toBe(
      false,
    );
    expect(processedNodes.find((node) => node.id === "child")?.selected).toBe(
      true,
    );
    expect(processedEdges[0].hidden).toBe(false);
  });

  it("should collapse all containers including nested ones in collapseAllContainers", () => {
    mockedComputeNestedUnitCounts.mockReturnValue(
      new Map([
        ["root", 2],
        ["nested", 1],
      ]),
    );

    const nodes = [
      createContainer({
        id: "root",
        data: { collapsed: false, elementType: "container" },
      }),
      createContainer({
        id: "nested",
        parentId: "root",
        data: { collapsed: false, elementType: "container" },
      }),
      createNode({
        id: "child",
        parentId: "nested",
        selected: true,
      }),
      createNode({
        id: "outside",
      }),
    ];

    const edges = [
      createEdge({
        id: "edge-1",
        source: "outside",
        target: "child",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    act(() => {
      result.current.collapseAllContainers();
    });

    expect(setNodes).toHaveBeenCalledTimes(1);
    expect(setEdges).toHaveBeenCalledTimes(1);
    expect(structureChanged).toHaveBeenCalledWith(["root", "nested"]);

    const processedNodes = setNodes.mock.calls[0][0] as ChainGraphNode[];
    const processedEdges = setEdges.mock.calls[0][0] as Edge[];

    const root = processedNodes.find((node) => node.id === "root");
    const nested = processedNodes.find((node) => node.id === "nested");
    const child = processedNodes.find((node) => node.id === "child");

    expect(root?.data?.collapsed).toBe(true);
    expect(nested?.data?.collapsed).toBe(true);

    expect(root?.hidden).toBe(false);
    expect(nested?.hidden).toBe(true);
    expect(child?.hidden).toBe(true);
    expect(child?.selected).toBe(false);

    expect(processedEdges[0].hidden).toBe(true);
  });

  it("should attach onToggleCollapse only to container nodes and callback should work", () => {
    const nodes = [
      createContainer({
        id: "c1",
        data: { collapsed: false, elementType: "container" },
      }),
      createNode({
        id: "u1",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    const attachedNodes = result.current.attachToggle(nodes);

    expect((attachedNodes[0].data as any).onToggleCollapse).toEqual(
      expect.any(Function),
    );
    expect((attachedNodes[1].data as any).onToggleCollapse).toBeUndefined();

    act(() => {
      (attachedNodes[0].data as any).onToggleCollapse();
    });

    expect(setNodes).toHaveBeenCalledTimes(1);
    expect(setEdges).toHaveBeenCalledTimes(1);
    expect(structureChanged).toHaveBeenCalledWith(["c1"]);
  });

  it("should reapply node visibility and proxy handles for collapsed group container", () => {
    const nodes = [
      createContainer({
        id: "root",
        data: { collapsed: true, elementType: "container" },
      }),
      createNode({
        id: "child",
        parentId: "root",
        selected: true,
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    const processedNodes = result.current.reapplyNodesVisibility(nodes);

    const root = processedNodes.find((node) => node.id === "root");
    const child = processedNodes.find((node) => node.id === "child");

    expect(root?.hidden).toBe(false);
    expect(root?.data?.inputEnabled).toBe(true);
    expect(root?.data?.outputEnabled).toBe(true);
    expect(root?.connectable).toBe(false);

    expect(child?.hidden).toBe(true);
    expect(child?.selected).toBe(false);
  });

  it("should hide edges connected to hidden nodes and remove null handles", () => {
    const nodes = [
      createContainer({
        id: "root",
        data: { collapsed: true, elementType: "container" },
      }),
      createNode({
        id: "child",
        parentId: "root",
      }),
      createNode({
        id: "outside",
      }),
    ];

    const edges = [
      createEdge({
        id: "edge-1",
        source: "outside",
        target: "child",
        sourceHandle: null as any,
        targetHandle: null as any,
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    const processedEdges = result.current.reapplyEdgesVisibility(nodes, edges);

    expect(processedEdges).toHaveLength(1);
    expect(processedEdges[0].hidden).toBe(true);
    expect(processedEdges[0]).not.toHaveProperty("sourceHandle");
    expect(processedEdges[0]).not.toHaveProperty("targetHandle");
  });

  it("should build decorative edges for hidden endpoints", () => {
    const nodes = [
      createContainer({
        id: "root",
        data: { collapsed: true, elementType: "container" },
      }),
      createNode({
        id: "child",
        parentId: "root",
      }),
      createNode({
        id: "outside",
      }),
    ];

    const edges = [
      createEdge({
        id: "edge-1",
        source: "outside",
        target: "child",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const { result } = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    const decorativeEdges = result.current.buildDecorativeEdges(nodes, edges);

    expect(decorativeEdges).toHaveLength(1);
    expect(decorativeEdges[0]).toMatchObject({
      id: "decorative:edge-1:outside:root",
      source: "outside",
      target: "root",
      selectable: true,
      deletable: true,
      data: {
        decorative: true,
        originalEdgeId: "edge-1",
        expandContainerIds: ["root"],
      },
      zIndex: 1,
    });
  });

  it("should use latest nodes after rerender when toggle is called", () => {
    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const initialNodes = [
      createContainer({
        id: "root",
        data: { collapsed: false, elementType: "container" },
      }),
    ];

    const updatedNodes = [
      createContainer({
        id: "root",
        data: { collapsed: true, elementType: "container" },
      }),
    ];

    const { result, rerender } = renderHook(
      ({ hookNodes, hookEdges }) =>
        useExpandCollapse(
          hookNodes,
          setNodes,
          hookEdges,
          setEdges,
          structureChanged,
        ),
      {
        initialProps: {
          hookNodes: initialNodes,
          hookEdges: [] as Edge[],
        },
      },
    );

    rerender({
      hookNodes: updatedNodes,
      hookEdges: [],
    });

    act(() => {
      result.current.toggle("root");
    });

    const processedNodes = setNodes.mock.calls[0][0] as ChainGraphNode[];

    expect(
      processedNodes.find((node) => node.id === "root")?.data?.collapsed,
    ).toBe(false);
  });
});
