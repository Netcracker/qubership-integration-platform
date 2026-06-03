/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation(((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })) as (...args: unknown[]) => unknown),
});

import {beforeEach, describe, expect, it, jest} from "@jest/globals";
import {act, renderHook} from "@testing-library/react";
import type {Edge} from "@xyflow/react";

import {useExpandCollapse} from "../../../src/hooks/graph/useExpandCollapse";
import type {ChainGraphNode} from "../../../src/components/graph/nodes/ChainGraphNodeTypes";
import {computeNestedUnitCounts} from "../../../src/misc/chain-graph-utils.ts";

jest.mock("../../../src/misc/chain-graph-utils.ts", () => ({
  computeNestedUnitCounts: jest.fn(),
}));

const mockedComputeNestedUnitCounts =
  computeNestedUnitCounts as jest.MockedFunction<
    typeof computeNestedUnitCounts
  >;

function createNode(
  overrides: Omit<Partial<ChainGraphNode>, "data"> & {
    data?: Record<string, unknown>;
  } = {},
): ChainGraphNode {
  const {data, ...rest} = overrides;

  return {
    id: "node",
    type: "unit",
    position: {x: 0, y: 0},
    data: data ?? {},
    ...rest,
  } as ChainGraphNode;
}

function createContainer(
  overrides: Omit<Partial<ChainGraphNode>, "data"> & {
    data?: Record<string, unknown>;
  } = {},
): ChainGraphNode {
  const {data, ...rest} = overrides;

  return {
    id: "container",
    type: "container",
    position: {x: 0, y: 0},
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
        data: {collapsed: false, elementType: "container"},
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

    const {result} = renderHook(() =>
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

    expect(child?.hidden).toBe(true);
    expect(child?.selected).toBe(false);

    expect(processedEdges[0].hidden).toBe(true);
  });

  it("should call structureChanged with parent id when toggling nested container", () => {
    const nodes = [
      createContainer({
        id: "parent",
        data: {collapsed: false, elementType: "container"},
      }),
      createContainer({
        id: "nested",
        parentId: "parent",
        data: {collapsed: false, elementType: "container"},
      }),
      createNode({
        id: "child",
        parentId: "nested",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    act(() => {
      result.current.toggle("nested");
    });

    expect(structureChanged).toHaveBeenCalledWith(["nested"]);
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
        data: {collapsed: true, elementType: "container"},
      }),
      createContainer({
        id: "c2",
        data: {collapsed: true, elementType: "container"},
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

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    act(() => {
      result.current.expandContainers(["c1", "c1", ""]);
    });

    expect(setNodes).toHaveBeenCalledTimes(1);
    expect(setEdges).toHaveBeenCalledTimes(1);
    expect(structureChanged).not.toHaveBeenCalled();

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

    const {result} = renderHook(() =>
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
        data: {collapsed: true, elementType: "container"},
      }),
      createContainer({
        id: "nested",
        parentId: "root",
        data: {collapsed: true, elementType: "container"},
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

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    act(() => {
      result.current.expandAllContainers();
    });

    expect(setNodes).toHaveBeenCalledTimes(1);
    expect(setEdges).toHaveBeenCalledTimes(1);
    expect(structureChanged).not.toHaveBeenCalled();

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
        data: {collapsed: false, elementType: "container"},
      }),
      createContainer({
        id: "nested",
        parentId: "root",
        data: {collapsed: false, elementType: "container"},
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

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    act(() => {
      result.current.collapseAllContainers();
    });

    expect(setNodes).toHaveBeenCalledTimes(1);
    expect(setEdges).toHaveBeenCalledTimes(1);
    expect(structureChanged).not.toHaveBeenCalled();

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
        data: {collapsed: false, elementType: "container"},
      }),
      createNode({
        id: "u1",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
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
        data: {collapsed: true, elementType: "container"},
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

    const {result} = renderHook(() =>
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
        data: {collapsed: true, elementType: "container"},
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
        sourceHandle: null,
        targetHandle: null,
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
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
        data: {collapsed: true, elementType: "container"},
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

    const {result} = renderHook(() =>
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
        data: {collapsed: false, elementType: "container"},
      }),
    ];

    const updatedNodes = [
      createContainer({
        id: "root",
        data: {collapsed: true, elementType: "container"},
      }),
    ];

    const {result, rerender} = renderHook(
      ({hookNodes, hookEdges}) =>
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

  it("should set nested unit counts only for container nodes", () => {
    mockedComputeNestedUnitCounts.mockReturnValue(
      new Map([
        ["root", 2],
        ["empty", 0],
      ]),
    );

    const nodes = [
      createContainer({
        id: "root",
        data: {collapsed: false, elementType: "container"},
      }),
      createContainer({
        id: "empty",
        data: {collapsed: false, elementType: "container"},
      }),
      createNode({
        id: "unit",
        data: {unitCount: 999},
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    const processedNodes = result.current.setNestedUnitCounts(nodes);

    expect(mockedComputeNestedUnitCounts).toHaveBeenCalledWith(nodes);

    expect(
      processedNodes.find((node) => node.id === "root")?.data?.unitCount,
    ).toBe(2);
    expect(
      processedNodes.find((node) => node.id === "empty")?.data?.unitCount,
    ).toBe(0);

    expect(
      processedNodes.find((node) => node.id === "unit")?.data?.unitCount,
    ).toBe(999);
  });

  it("should do nothing when toggle is called for missing or non-container node", () => {
    const nodes = [
      createNode({
        id: "unit",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    act(() => {
      result.current.toggle("missing");
      result.current.toggle("unit");
    });

    expect(setNodes).not.toHaveBeenCalled();
    expect(setEdges).not.toHaveBeenCalled();
    expect(structureChanged).not.toHaveBeenCalled();
  });

  it("should keep descendants hidden when collapsed container is toggled to expanded state", () => {
    const nodes = [
      createContainer({
        id: "root",
        data: {collapsed: true, elementType: "container"},
      }),
      createContainer({
        id: "nested",
        parentId: "root",
        data: {collapsed: false, elementType: "container"},
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

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    act(() => {
      result.current.toggle("root");
    });

    expect(structureChanged).toHaveBeenCalledWith(["root"]);

    const processedNodes = setNodes.mock.calls[0][0] as ChainGraphNode[];
    const processedEdges = setEdges.mock.calls[0][0] as Edge[];

    const root = processedNodes.find((node) => node.id === "root");
    const nested = processedNodes.find((node) => node.id === "nested");
    const child = processedNodes.find((node) => node.id === "child");

    expect(root?.data?.collapsed).toBe(false);
    expect(root?.hidden).toBe(false);

    expect(nested?.hidden).toBe(true);
    expect(child?.hidden).toBe(true);
    expect(child?.selected).toBe(false);

    expect(processedEdges[0].hidden).toBe(true);
  });

  it("should clear proxy handles for expanded group container", () => {
    const nodes = [
      createContainer({
        id: "root",
        connectable: true,
        data: {
          collapsed: false,
          elementType: "container",
          inputEnabled: true,
          outputEnabled: true,
        },
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    const processedNodes = result.current.reapplyNodesVisibility(nodes);
    const root = processedNodes.find((node) => node.id === "root");

    expect(root?.data?.inputEnabled).toBeUndefined();
    expect(root?.data?.outputEnabled).toBeUndefined();
    expect(root?.connectable).toBe(false);
  });

  it("should not apply group proxy handles to non-group container", () => {
    const nodes = [
      createContainer({
        id: "root",
        connectable: true,
        data: {
          collapsed: true,
          elementType: "script",
          inputEnabled: false,
          outputEnabled: false,
        },
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    const processedNodes = result.current.reapplyNodesVisibility(nodes);
    const root = processedNodes.find((node) => node.id === "root");

    expect(root?.data?.inputEnabled).toBe(false);
    expect(root?.data?.outputEnabled).toBe(false);
    expect(root?.connectable).toBe(true);
  });

  it("should keep visible edge visible and preserve existing handles", () => {
    const nodes = [
      createNode({
        id: "source",
      }),
      createNode({
        id: "target",
      }),
    ];

    const edges = [
      createEdge({
        id: "edge-1",
        source: "source",
        target: "target",
        sourceHandle: "source-handle",
        targetHandle: "target-handle",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    const processedEdges = result.current.reapplyEdgesVisibility(nodes, edges);

    expect(processedEdges[0]).toMatchObject({
      id: "edge-1",
      hidden: false,
      sourceHandle: "source-handle",
      targetHandle: "target-handle",
    });
  });

  it("should not build decorative edges when both endpoints are visible", () => {
    const nodes = [
      createNode({
        id: "source",
      }),
      createNode({
        id: "target",
      }),
    ];

    const edges = [
      createEdge({
        id: "edge-1",
        source: "source",
        target: "target",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    expect(result.current.buildDecorativeEdges(nodes, edges)).toEqual([]);
  });

  it("should skip decorative edge when hidden endpoints resolve to same collapsed proxy", () => {
    const nodes = [
      createContainer({
        id: "root",
        data: {collapsed: true, elementType: "container"},
      }),
      createNode({
        id: "source",
        parentId: "root",
      }),
      createNode({
        id: "target",
        parentId: "root",
      }),
    ];

    const edges = [
      createEdge({
        id: "edge-1",
        source: "source",
        target: "target",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    expect(result.current.buildDecorativeEdges(nodes, edges)).toEqual([]);
  });

  it("should deduplicate decorative edges and preserve visual edge properties", () => {
    const nodes = [
      createContainer({
        id: "root",
        data: {collapsed: true, elementType: "container"},
      }),
      createNode({
        id: "child",
        parentId: "root",
      }),
      createNode({
        id: "outside",
      }),
    ];

    const edge = createEdge({
      id: "edge-1",
      source: "outside",
      target: "child",
      type: "smoothstep",
      label: "Edge label",
      className: "edge-class",
      animated: true,
      style: {strokeWidth: 2},
      zIndex: 5,
    });

    const edges = [edge, {...edge}];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, edges, setEdges, structureChanged),
    );

    const decorativeEdges = result.current.buildDecorativeEdges(nodes, edges);

    expect(decorativeEdges).toHaveLength(1);
    expect(decorativeEdges[0]).toMatchObject({
      id: "decorative:edge-1:outside:root",
      source: "outside",
      target: "root",
      type: "smoothstep",
      label: "Edge label",
      className: "edge-class",
      animated: true,
      style: {strokeWidth: 2},
      zIndex: 6,
    });
  });

  it("should do nothing in expandContainers when requested nodes are not collapsed containers", () => {
    const nodes = [
      createContainer({
        id: "expanded",
        data: {collapsed: false, elementType: "container"},
      }),
      createNode({
        id: "unit",
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    act(() => {
      result.current.expandContainers(["expanded", "unit", "missing"]);
    });

    expect(setNodes).not.toHaveBeenCalled();
    expect(setEdges).not.toHaveBeenCalled();
    expect(structureChanged).not.toHaveBeenCalled();
  });

  it("should do nothing in expandAllContainers when all containers are already expanded", () => {
    const nodes = [
      createContainer({
        id: "root",
        data: {collapsed: false, elementType: "container"},
      }),
      createContainer({
        id: "nested",
        parentId: "root",
        data: {collapsed: false, elementType: "container"},
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    act(() => {
      result.current.expandAllContainers();
    });

    expect(setNodes).not.toHaveBeenCalled();
    expect(setEdges).not.toHaveBeenCalled();
    expect(structureChanged).not.toHaveBeenCalled();
  });

  it("should do nothing in collapseAllContainers when all containers are already collapsed", () => {
    const nodes = [
      createContainer({
        id: "root",
        data: {collapsed: true, elementType: "container"},
      }),
      createContainer({
        id: "nested",
        parentId: "root",
        data: {collapsed: true, elementType: "container"},
      }),
    ];

    const setNodes = jest.fn();
    const setEdges = jest.fn();
    const structureChanged = jest.fn();

    const {result} = renderHook(() =>
      useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
    );

    act(() => {
      result.current.collapseAllContainers();
    });

    expect(setNodes).not.toHaveBeenCalled();
    expect(setEdges).not.toHaveBeenCalled();
    expect(structureChanged).not.toHaveBeenCalled();
  });

  it("should schedule structureChanged for expandContainers on animation frame", () => {
    const originalRequestAnimationFrame = window.requestAnimationFrame;
    const originalCancelAnimationFrame = window.cancelAnimationFrame;

    let frameCallback: FrameRequestCallback | undefined;

    Object.defineProperty(window, "requestAnimationFrame", {
      writable: true,
      configurable: true,
      value: jest.fn((callback: FrameRequestCallback): number => {
        frameCallback = callback;
        return 7;
      }),
    });

    Object.defineProperty(window, "cancelAnimationFrame", {
      writable: true,
      configurable: true,
      value: jest.fn(),
    });

    try {
      const nodes = [
        createContainer({
          id: "c1",
          data: {collapsed: true, elementType: "container"},
        }),
        createContainer({
          id: "c2",
          data: {collapsed: true, elementType: "container"},
        }),
      ];

      const setNodes = jest.fn();
      const setEdges = jest.fn();
      const structureChanged = jest.fn();

      const {result} = renderHook(() =>
        useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
      );

      act(() => {
        result.current.expandContainers(["c1", "c2", "c1"]);
      });

      expect(setNodes).toHaveBeenCalledTimes(1);
      expect(setEdges).toHaveBeenCalledTimes(1);
      expect(structureChanged).not.toHaveBeenCalled();
      expect(window.requestAnimationFrame).toHaveBeenCalledTimes(1);

      act(() => {
        frameCallback?.(0);
      });

      expect(structureChanged).toHaveBeenCalledWith(["c1", "c2"]);
    } finally {
      Object.defineProperty(window, "requestAnimationFrame", {
        writable: true,
        configurable: true,
        value: originalRequestAnimationFrame,
      });
      Object.defineProperty(window, "cancelAnimationFrame", {
        writable: true,
        configurable: true,
        value: originalCancelAnimationFrame,
      });
    }
  });

  it("should cancel scheduled structureChanged on unmount", () => {
    const originalRequestAnimationFrame = window.requestAnimationFrame;
    const originalCancelAnimationFrame = window.cancelAnimationFrame;

    Object.defineProperty(window, "requestAnimationFrame", {
      writable: true,
      configurable: true,
      value: jest.fn((): number => 42),
    });

    Object.defineProperty(window, "cancelAnimationFrame", {
      writable: true,
      configurable: true,
      value: jest.fn(),
    });

    try {
      const nodes = [
        createContainer({
          id: "c1",
          data: {collapsed: true, elementType: "container"},
        }),
      ];

      const setNodes = jest.fn();
      const setEdges = jest.fn();
      const structureChanged = jest.fn();

      const {result, unmount} = renderHook(() =>
        useExpandCollapse(nodes, setNodes, [], setEdges, structureChanged),
      );

      act(() => {
        result.current.expandContainers(["c1"]);
      });

      unmount();

      expect(window.cancelAnimationFrame).toHaveBeenCalledWith(42);
      expect(structureChanged).not.toHaveBeenCalled();
    } finally {
      Object.defineProperty(window, "requestAnimationFrame", {
        writable: true,
        configurable: true,
        value: originalRequestAnimationFrame,
      });
      Object.defineProperty(window, "cancelAnimationFrame", {
        writable: true,
        configurable: true,
        value: originalCancelAnimationFrame,
      });
    }
  });
});
