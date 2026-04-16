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

import React from "react";
import { act, renderHook, waitFor } from "@testing-library/react";

const mockUseReactFlow = jest.fn();
const mockScreenToFlowPosition = jest.fn(
  ({ x, y }: { x: number; y: number }) => ({ x, y }),
);
const mockGetIntersectingNodes = jest.fn();

jest.mock("@xyflow/react", () => {
  const actual = jest.requireActual<typeof import("@xyflow/react")>(
    "@xyflow/react",
  );
  return {
    ...actual,
    useReactFlow: (...args: unknown[]) =>
      (mockUseReactFlow as (...a: unknown[]) => unknown)(...args),
  };
});

let mockIsLibraryLoading = false;
jest.mock("../../../src/components/LibraryContext", () => ({
  useLibraryContext: () => ({
    isLibraryLoading: mockIsLibraryLoading,
    libraryElements: [
      { name: "script", title: "Script", type: "script" },
      { name: "container", title: "Container", type: "container" },
      { name: "swimlane", title: "Swimlane", type: "swimlane" },
    ],
  }),
}));

const mockRequestFailed = jest.fn();
const mockErrorWithDetails = jest.fn();
jest.mock("../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
    errorWithDetails: mockErrorWithDetails,
  }),
}));

jest.mock("../../../src/hooks/graph/useAutoLayout", () => ({
  useAutoLayout: () => ({
    arrangeNodes: jest.fn((nodes: unknown[]) => Promise.resolve(nodes)),
    direction: "RIGHT",
    toggleDirection: jest.fn(),
  }),
}));

const mockExpandAllContainers = jest.fn();
const mockCollapseAllContainers = jest.fn();
jest.mock("../../../src/hooks/graph/useExpandCollapse", () => ({
  useExpandCollapse: () => ({
    attachToggle: (nodes: unknown[]) => nodes,
    setNestedUnitCounts: (nodes: unknown[]) => nodes,
    reapplyNodesVisibility: (nodes: unknown[]) => nodes,
    reapplyEdgesVisibility: (_nodes: unknown[], edges: unknown[]) => edges,
    expandAllContainers: mockExpandAllContainers,
    collapseAllContainers: mockCollapseAllContainers,
  }),
}));

jest.mock("../../../src/api/api", () => ({
  api: {
    getElements: jest.fn(),
    getConnections: jest.fn(),
    transferElement: jest.fn(),
    createConnection: jest.fn(),
    createElement: jest.fn(),
    deleteConnections: jest.fn(),
    deleteElements: jest.fn(),
  },
}));

jest.mock("../../../src/misc/chain-graph-utils", () => {
  const actual = jest.requireActual<
    typeof import("../../../src/misc/chain-graph-utils")
  >("../../../src/misc/chain-graph-utils");
  return {
    ...actual,
    getPossibleGraphIntersection: jest.fn(),
    getIntersectionParent: jest.fn(),
    findUpdatedElement: jest.fn(),
    collectChildren: jest.fn(() => []),
    getLeastCommonParent: jest.fn(() => undefined),
    getContainerIdsForEdges: jest.fn(() => []),
    getFakeNode: jest.fn((pos: { x: number; y: number }) => ({
      id: "fake",
      type: "fake",
      position: pos,
      data: {},
    })),
    getNodeFromElement: jest.fn(
      (element: { id: string; type: string; parentElementId?: string }) => ({
        id: element.id,
        type: element.type,
        position: { x: 0, y: 0 },
        data: { elementType: element.type },
        parentId: element.parentElementId,
      }),
    ),
    getLibraryElement: jest.fn(() => ({
      name: "default",
      title: "Default",
    })),
    getDataFromElement: jest.fn(
      (element: { type: string; name?: string }) => ({
        elementType: element.type,
        label: element.name,
      }),
    ),
    buildGraphNodes: jest.fn(() => []),
    applyHighlight: jest.fn((nodes: unknown[]) => nodes),
  };
});

import { api } from "../../../src/api/api";
import {
  getPossibleGraphIntersection,
  getIntersectionParent,
  findUpdatedElement,
  getContainerIdsForEdges,
  buildGraphNodes,
  getLeastCommonParent,
} from "../../../src/misc/chain-graph-utils";
import { useChainGraph } from "../../../src/hooks/graph/useChainGraph";
import type {
  ChainGraphNode,
  OnDeleteEvent,
} from "../../../src/components/graph/nodes/ChainGraphNodeTypes";
import type { EdgeChange, NodeChange, Edge } from "@xyflow/react";

type HookResult = ReturnType<typeof useChainGraph>;

const apiMock = api as unknown as {
  getElements: jest.Mock;
  getConnections: jest.Mock;
  transferElement: jest.Mock;
  createConnection: jest.Mock;
  createElement: jest.Mock;
  deleteConnections: jest.Mock;
  deleteElements: jest.Mock;
};

const initialNodes = [
  {
    id: "node-1",
    type: "unit",
    position: { x: 50, y: 50 },
    data: {},
    parentId: "container-1",
    selected: true,
  },
  {
    id: "container-1",
    type: "container",
    position: { x: 0, y: 0 },
    data: { elementType: "container" },
  },
  {
    id: "container-2",
    type: "container",
    position: { x: 200, y: 0 },
    data: { elementType: "container" },
  },
] as unknown as ChainGraphNode[];

const draggedNode = {
  id: "node-1",
  type: "unit",
  position: { x: 50, y: 50 },
  data: {},
  parentId: "container-1",
  selected: true,
} as unknown as ChainGraphNode;

const settle = async () => {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
};

const renderChainGraph = async (
  onChainUpdate?: jest.Mock,
  options?: { chainId?: string | undefined },
) => {
  const chainId = options && "chainId" in options ? options.chainId : "chain-1";
  const rendered = renderHook(() => useChainGraph(chainId, onChainUpdate));
  await settle();
  return rendered;
};

const withInitialNodes = async (onChainUpdate?: jest.Mock) => {
  const rendered = await renderChainGraph(onChainUpdate);
  act(() => {
    rendered.result.current.setNodes(initialNodes);
  });
  await waitFor(() => {
    expect(rendered.result.current.nodes.length).toBe(initialNodes.length);
  });
  return rendered;
};

describe("useChainGraph", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockIsLibraryLoading = false;
    apiMock.getElements.mockResolvedValue([]);
    apiMock.getConnections.mockResolvedValue([]);
    mockUseReactFlow.mockReturnValue({
      screenToFlowPosition: mockScreenToFlowPosition,
      getIntersectingNodes: mockGetIntersectingNodes,
    });
    mockGetIntersectingNodes.mockReturnValue([]);
  });

  describe("initialization", () => {
    it("loads elements and connections on mount", async () => {
      apiMock.getElements.mockResolvedValue([
        {
          id: "elem-1",
          type: "script",
          name: "Elem",
          description: "",
        },
      ]);
      apiMock.getConnections.mockResolvedValue([
        { id: "conn-1", from: "a", to: "b" },
      ]);

      const { result } = await renderChainGraph();

      expect(apiMock.getElements).toHaveBeenCalledWith("chain-1");
      expect(apiMock.getConnections).toHaveBeenCalledWith("chain-1");
      await waitFor(() => {
        expect(result.current.edges).toEqual([
          expect.objectContaining({
            id: "conn-1",
            source: "a",
            target: "b",
          }),
        ]);
      });
    });

    it("reports fetch errors via notificationService", async () => {
      apiMock.getElements.mockRejectedValue(new Error("boom"));
      await renderChainGraph();

      expect(mockRequestFailed).toHaveBeenCalledWith(
        expect.stringContaining("Failed to load elements or connections"),
        expect.any(Error),
      );
    });

    it("does not fetch when chainId is not provided", async () => {
      await renderChainGraph(undefined, { chainId: undefined });
      expect(apiMock.getElements).not.toHaveBeenCalled();
      expect(apiMock.getConnections).not.toHaveBeenCalled();
    });

    it("does not fetch while library is loading", async () => {
      mockIsLibraryLoading = true;
      await renderChainGraph();
      expect(apiMock.getElements).not.toHaveBeenCalled();
    });
  });

  describe("chainId guards", () => {
    it("onConnect, onDrop, onEdgesChange, onDelete all no-op without chainId", async () => {
      const { result } = await renderChainGraph(undefined, {
        chainId: undefined,
      });

      await act(async () => {
        await result.current.onConnect({
          source: "a",
          target: "b",
          sourceHandle: null,
          targetHandle: null,
        });
      });
      expect(apiMock.createConnection).not.toHaveBeenCalled();

      await act(async () => {
        await result.current.onDrop({
          preventDefault: jest.fn(),
          clientX: 0,
          clientY: 0,
          dataTransfer: { getData: jest.fn(() => "script") },
        } as unknown as React.DragEvent);
      });
      expect(apiMock.createElement).not.toHaveBeenCalled();

      act(() => {
        result.current.onEdgesChange([
          { id: "e", type: "select", selected: true },
        ]);
      });
      expect(result.current.edges).toEqual([]);

      await act(async () => {
        await result.current.onDelete({ nodes: [], edges: [] });
      });
      expect(apiMock.deleteElements).not.toHaveBeenCalled();
    });
  });

  describe("onConnect", () => {
    it("creates a connection, adds edge, and calls onChainUpdate", async () => {
      apiMock.createConnection.mockResolvedValue({
        createdDependencies: [{ id: "edge-1" }],
      });
      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onConnect({
          source: "node-1",
          target: "container-2",
          sourceHandle: null,
          targetHandle: null,
        });
      });

      expect(apiMock.createConnection).toHaveBeenCalledWith(
        { from: "node-1", to: "container-2" },
        "chain-1",
      );
      expect(onChainUpdate).toHaveBeenCalled();
      expect(result.current.edges).toContainEqual(
        expect.objectContaining({ id: "edge-1" }),
      );
    });

    it("does not call structureChanged when neither endpoint has parentId", async () => {
      apiMock.createConnection.mockResolvedValue({
        createdDependencies: [{ id: "edge-root" }],
      });
      const rootNodesOnly = [
        {
          id: "root-a",
          type: "unit",
          position: { x: 0, y: 0 },
          data: {},
        },
        {
          id: "root-b",
          type: "unit",
          position: { x: 100, y: 0 },
          data: {},
        },
      ] as unknown as ChainGraphNode[];

      const { result } = await renderChainGraph();
      act(() => {
        result.current.setNodes(rootNodesOnly);
      });
      await waitFor(() => {
        expect(result.current.nodes.length).toBe(rootNodesOnly.length);
      });

      await act(async () => {
        await result.current.onConnect({
          source: "root-a",
          target: "root-b",
          sourceHandle: null,
          targetHandle: null,
        });
      });

      expect(apiMock.createConnection).toHaveBeenCalled();
    });

    it("skips setEdges when backend returns no createdDependencies", async () => {
      apiMock.createConnection.mockResolvedValue({ createdDependencies: [] });
      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onConnect({
          source: "node-1",
          target: "container-2",
          sourceHandle: null,
          targetHandle: null,
        });
      });

      expect(onChainUpdate).not.toHaveBeenCalled();
    });

    it("reports error via notificationService on failure", async () => {
      apiMock.createConnection.mockRejectedValue(new Error("nope"));
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onConnect({
          source: "node-1",
          target: "container-2",
          sourceHandle: null,
          targetHandle: null,
        });
      });

      expect(mockRequestFailed).toHaveBeenCalledWith(
        expect.stringContaining("Failed to create connection"),
        expect.any(Error),
      );
    });
  });

  describe("onDrop", () => {
    const makeDropEvent = (
      dataValue: string | undefined,
    ): React.DragEvent => {
      const dataTransfer = {
        getData: jest.fn(() => dataValue ?? ""),
        dropEffect: "move" as const,
      };
      return {
        preventDefault: jest.fn(),
        clientX: 100,
        clientY: 200,
        dataTransfer,
      } as unknown as React.DragEvent;
    };

    it("creates element and calls onChainUpdate on drop", async () => {
      apiMock.createElement.mockResolvedValue({
        createdElements: [
          { id: "new-elem", type: "script", name: "New", description: "" },
        ],
        updatedElements: [],
      });
      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onDrop(makeDropEvent("script"));
      });

      expect(apiMock.createElement).toHaveBeenCalledWith(
        expect.objectContaining({ type: "script" }),
        "chain-1",
      );
      expect(onChainUpdate).toHaveBeenCalled();
    });

    it("returns early if dataTransfer has no element name", async () => {
      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onDrop(makeDropEvent(""));
      });

      expect(apiMock.createElement).not.toHaveBeenCalled();
      expect(onChainUpdate).not.toHaveBeenCalled();
    });

    it("reports failure via notificationService", async () => {
      apiMock.createElement.mockRejectedValue(new Error("create failed"));
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDrop(makeDropEvent("script"));
      });

      expect(mockRequestFailed).toHaveBeenCalledWith(
        expect.stringContaining("Failed to create element"),
        expect.any(Error),
      );
    });

    it("sets parentElementId when dropping on a container", async () => {
      mockGetIntersectingNodes.mockReturnValue([
        {
          id: "container-2",
          type: "container",
          width: 200,
          height: 200,
        },
      ]);
      apiMock.createElement.mockResolvedValue({
        createdElements: [
          {
            id: "new-elem",
            type: "script",
            name: "New",
            description: "",
            parentElementId: "container-2",
          },
        ],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDrop(makeDropEvent("script"));
      });

      expect(apiMock.createElement).toHaveBeenCalledWith(
        expect.objectContaining({
          type: "script",
          parentElementId: "container-2",
        }),
        "chain-1",
      );
    });

    it("sets swimlaneId when dropping on a swimlane", async () => {
      mockGetIntersectingNodes.mockReturnValue([
        {
          id: "swimlane-1",
          type: "swimlane",
          width: 400,
          height: 400,
        },
      ]);
      apiMock.createElement.mockResolvedValue({
        createdElements: [
          {
            id: "new-elem",
            type: "script",
            name: "New",
            description: "",
            swimlaneId: "swimlane-1",
          },
        ],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDrop(makeDropEvent("script"));
      });

      expect(apiMock.createElement).toHaveBeenCalledWith(
        expect.objectContaining({
          type: "script",
          swimlaneId: "swimlane-1",
        }),
        "chain-1",
      );
    });

    it("returns early when backend returns no createdElements", async () => {
      apiMock.createElement.mockResolvedValue({
        createdElements: [],
        updatedElements: [],
      });
      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onDrop(makeDropEvent("script"));
      });

      expect(onChainUpdate).not.toHaveBeenCalled();
    });

    it("handles intersecting nodes without width/height", async () => {
      mockGetIntersectingNodes.mockReturnValue([
        { id: "container-2", type: "container" },
      ]);
      apiMock.createElement.mockResolvedValue({
        createdElements: [
          {
            id: "new-elem",
            type: "script",
            name: "New",
            description: "",
            parentElementId: "container-2",
          },
        ],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDrop(makeDropEvent("script"));
      });

      expect(apiMock.createElement).toHaveBeenCalled();
    });

    it("returns early when getNodeFromElement returns falsy", async () => {
      const utilsMock = jest.requireMock<{
        getNodeFromElement: jest.Mock;
      }>("../../../src/misc/chain-graph-utils");
      utilsMock.getNodeFromElement.mockReturnValueOnce(null);

      apiMock.createElement.mockResolvedValue({
        createdElements: [
          {
            id: "new-elem",
            type: "script",
            name: "New",
            description: "",
          },
        ],
        updatedElements: [],
      });
      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onDrop(makeDropEvent("script"));
      });

      expect(onChainUpdate).not.toHaveBeenCalled();
    });

    it("uses newNode.parentId for structureChanged when no parentNode", async () => {
      apiMock.createElement.mockResolvedValue({
        createdElements: [
          {
            id: "new-elem",
            type: "script",
            name: "New",
            description: "",
            parentElementId: "container-2",
          },
        ],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDrop(makeDropEvent("script"));
      });

      expect(apiMock.createElement).toHaveBeenCalled();
    });

    it("picks the smallest container among multiple intersections", async () => {
      mockGetIntersectingNodes.mockReturnValue([
        { id: "big", type: "container", width: 400, height: 400 },
        { id: "small", type: "container", width: 100, height: 100 },
      ]);
      apiMock.createElement.mockResolvedValue({
        createdElements: [
          {
            id: "new-elem",
            type: "script",
            name: "New",
            description: "",
            parentElementId: "small",
          },
        ],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDrop(makeDropEvent("script"));
      });

      expect(apiMock.createElement).toHaveBeenCalledWith(
        expect.objectContaining({ parentElementId: "small" }),
        "chain-1",
      );
    });

    it("builds child nodes when createdElement has children", async () => {
      apiMock.createElement.mockResolvedValue({
        createdElements: [
          {
            id: "new-parent",
            type: "container",
            name: "P",
            description: "",
            children: [
              {
                id: "child-1",
                type: "script",
                name: "C",
                description: "",
              },
            ],
          },
        ],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDrop(makeDropEvent("container"));
      });

      expect(apiMock.createElement).toHaveBeenCalled();
    });
  });

  describe("onDelete", () => {
    it("deletes nodes and calls onChainUpdate", async () => {
      apiMock.deleteElements.mockResolvedValue({
        removedElements: [{ id: "node-1" }],
        removedDependencies: [],
        updatedElements: [],
      });
      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      const changes: OnDeleteEvent = {
        nodes: [initialNodes[0]],
        edges: [],
      };

      await act(async () => {
        await result.current.onDelete(changes);
      });

      expect(apiMock.deleteElements).toHaveBeenCalledWith(
        ["node-1"],
        "chain-1",
      );
      expect(onChainUpdate).toHaveBeenCalled();
    });

    it("deletes standalone edges via deleteConnections", async () => {
      apiMock.deleteConnections.mockResolvedValue({
        removedDependencies: [{ id: "edge-1" }],
      });
      apiMock.deleteElements.mockResolvedValue({
        removedElements: [],
        removedDependencies: [],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      const standaloneEdge: Edge = {
        id: "edge-1",
        source: "container-1",
        target: "container-2",
      };

      await act(async () => {
        await result.current.onDelete({ nodes: [], edges: [standaloneEdge] });
      });

      expect(apiMock.deleteConnections).toHaveBeenCalledWith(
        ["edge-1"],
        "chain-1",
      );
    });

    it("resolves decorative edges to original ids before calling deleteConnections", async () => {
      apiMock.deleteConnections.mockResolvedValue({
        removedDependencies: [{ id: "orig-edge" }],
      });
      apiMock.deleteElements.mockResolvedValue({
        removedElements: [],
        removedDependencies: [],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      const decorativeEdge: Edge = {
        id: "decorative:orig-edge",
        source: "container-1",
        target: "container-2",
        data: {
          decorative: true,
          originalEdgeId: "orig-edge",
          originalSource: "container-1",
          originalTarget: "container-2",
        },
      };

      await act(async () => {
        await result.current.onDelete({ nodes: [], edges: [decorativeEdge] });
      });

      expect(apiMock.deleteConnections).toHaveBeenCalledWith(
        ["orig-edge"],
        "chain-1",
      );
    });

    it("reports deleteConnections failure via notificationService", async () => {
      apiMock.deleteConnections.mockRejectedValue(
        new Error("delete connections failed"),
      );
      apiMock.deleteElements.mockResolvedValue({
        removedElements: [],
        removedDependencies: [],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      const standaloneEdge: Edge = {
        id: "edge-1",
        source: "container-1",
        target: "container-2",
      };

      await act(async () => {
        await result.current.onDelete({ nodes: [], edges: [standaloneEdge] });
      });

      expect(mockRequestFailed).toHaveBeenCalledWith(
        expect.stringContaining("Failed to delete connections"),
        expect.any(Error),
      );
    });

    it("does not call deleteConnections when all edges belong to deleted nodes", async () => {
      apiMock.deleteElements.mockResolvedValue({
        removedElements: [{ id: "node-1" }],
        removedDependencies: [{ id: "edge-1" }],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDelete({
          nodes: [initialNodes[0]],
          edges: [
            {
              id: "edge-1",
              source: "node-1",
              target: "container-2",
            },
          ],
        });
      });

      expect(apiMock.deleteConnections).not.toHaveBeenCalled();
      expect(apiMock.deleteElements).toHaveBeenCalledWith(
        ["node-1"],
        "chain-1",
      );
    });

    it("adds affected parents from getContainerIdsForEdges", async () => {
      (getContainerIdsForEdges as jest.Mock).mockReturnValue(["container-x"]);
      apiMock.deleteElements.mockResolvedValue({
        removedElements: [{ id: "node-1" }],
        removedDependencies: [],
        updatedElements: [],
      });
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDelete({
          nodes: [initialNodes[0]],
          edges: [],
        });
      });

      expect(getContainerIdsForEdges).toHaveBeenCalled();
      expect(apiMock.deleteElements).toHaveBeenCalled();
    });

    it("collects removedDependencies from deleteElements response", async () => {
      apiMock.deleteElements.mockResolvedValue({
        removedElements: [{ id: "node-1" }],
        removedDependencies: [{ id: "edge-orphan" }],
        updatedElements: [],
      });
      (buildGraphNodes as jest.Mock).mockReturnValue([]);
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDelete({
          nodes: [initialNodes[0]],
          edges: [],
        });
      });

      expect(apiMock.deleteElements).toHaveBeenCalled();
    });

    it("filters edges by deletedEdgeIds when edges exist", async () => {
      apiMock.getConnections.mockResolvedValue([
        { id: "keep-edge", from: "container-1", to: "container-2" },
        { id: "to-delete", from: "node-1", to: "container-2" },
      ]);
      apiMock.deleteElements.mockResolvedValue({
        removedElements: [{ id: "node-1" }],
        removedDependencies: [{ id: "to-delete" }],
        updatedElements: [],
      });

      const { result } = await withInitialNodes();
      await waitFor(() => {
        expect(result.current.edges.length).toBeGreaterThan(0);
      });

      await act(async () => {
        await result.current.onDelete({
          nodes: [initialNodes[0]],
          edges: [],
        });
      });

      expect(apiMock.deleteElements).toHaveBeenCalled();
    });

    it("handles updatedElements in deleteElements response", async () => {
      const updatedNode = {
        id: "other",
        type: "unit",
        position: { x: 0, y: 0 },
        data: {},
      } as unknown as ChainGraphNode;
      (buildGraphNodes as jest.Mock).mockReturnValue([updatedNode]);
      apiMock.deleteElements.mockResolvedValue({
        removedElements: [{ id: "node-1" }],
        removedDependencies: [],
        updatedElements: [
          { id: "other", type: "unit", name: "Other", description: "" },
        ],
      });
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onDelete({
          nodes: [initialNodes[0]],
          edges: [],
        });
      });

      expect(buildGraphNodes).toHaveBeenCalled();
    });

    it("reports element delete failure via notificationService", async () => {
      apiMock.deleteElements.mockRejectedValue(new Error("delete failed"));
      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onDelete({
          nodes: [initialNodes[0]],
          edges: [],
        });
      });

      expect(mockRequestFailed).toHaveBeenCalledWith(
        expect.stringContaining("Failed to delete element"),
        expect.any(Error),
      );
      expect(onChainUpdate).not.toHaveBeenCalled();
    });
  });

  describe("decorativeEdges", () => {
    it("exposes decorative edges for hidden source/target pairs", async () => {
      const hiddenContainer = {
        id: "container-h",
        type: "container",
        position: { x: 0, y: 0 },
        data: { elementType: "container" },
        hidden: true,
      } as unknown as ChainGraphNode;
      const visibleParent = {
        id: "container-p",
        type: "container",
        position: { x: 0, y: 0 },
        data: { elementType: "container" },
      } as unknown as ChainGraphNode;
      const hiddenChild = {
        id: "child",
        type: "unit",
        position: { x: 0, y: 0 },
        data: {},
        parentId: "container-h",
        hidden: true,
      } as unknown as ChainGraphNode;
      const outside = {
        id: "outside",
        type: "unit",
        position: { x: 0, y: 0 },
        data: {},
      } as unknown as ChainGraphNode;

      (hiddenContainer as unknown as { parentId?: string }).parentId =
        "container-p";

      apiMock.getElements.mockResolvedValue([]);
      apiMock.getConnections.mockResolvedValue([]);
      const { result } = await renderChainGraph();

      act(() => {
        result.current.setNodes([
          visibleParent,
          hiddenContainer,
          hiddenChild,
          outside,
        ]);
        result.current.setEdges([
          { id: "edge-1", source: "child", target: "outside" },
        ]);
      });

      await waitFor(() => {
        expect(result.current.decorativeEdges.length).toBe(1);
      });
      expect(result.current.decorativeEdges[0]).toEqual(
        expect.objectContaining({
          id: "decorative:edge-1",
          source: "container-p",
          target: "outside",
        }),
      );
    });
  });

  describe("onEdgesChange", () => {
    it("filters out remove changes", async () => {
      apiMock.getConnections.mockResolvedValue([
        { id: "edge-1", from: "a", to: "b" },
      ]);
      const { result } = await renderChainGraph();

      await waitFor(() => {
        expect(result.current.edges.length).toBe(1);
      });

      const changes: EdgeChange[] = [
        { id: "edge-1", type: "remove" },
      ];
      act(() => {
        result.current.onEdgesChange(changes);
      });

      expect(result.current.edges).toHaveLength(1);
    });

    it("applies changes to decorative edges", async () => {
      const { result } = await withInitialNodes();

      act(() => {
        result.current.onEdgesChange([
          {
            id: "decorative:edge-1",
            type: "select",
            selected: true,
          } as EdgeChange,
        ]);
      });

      expect(result.current.edges).toEqual(expect.any(Array));
    });

    it("applies non-remove base changes", async () => {
      apiMock.getConnections.mockResolvedValue([
        { id: "edge-1", from: "a", to: "b" },
      ]);
      const { result } = await renderChainGraph();

      await waitFor(() => {
        expect(result.current.edges.length).toBe(1);
      });

      act(() => {
        result.current.onEdgesChange([
          { id: "edge-1", type: "select", selected: true },
        ]);
      });

      await waitFor(() => {
        expect(result.current.edges[0].selected).toBe(true);
      });
    });
  });

  describe("onNodesChange", () => {
    it("ignores remove changes", async () => {
      const { result } = await withInitialNodes();
      const initialLength = result.current.nodes.length;

      act(() => {
        const changes: NodeChange<ChainGraphNode>[] = [
          { id: "node-1", type: "remove" },
        ];
        result.current.onNodesChange(changes);
      });

      expect(result.current.nodes).toHaveLength(initialLength);
    });

    it("applies non-remove changes", async () => {
      const { result } = await withInitialNodes();

      act(() => {
        result.current.onNodesChange([
          { id: "node-1", type: "select", selected: false },
        ]);
      });

      const updated = result.current.nodes.find((n) => n.id === "node-1");
      expect(updated?.selected).toBe(false);
    });

    it("does nothing without chainId", async () => {
      const { result } = await renderChainGraph(undefined, {
        chainId: undefined,
      });
      act(() => {
        result.current.onNodesChange([
          { id: "x", type: "select", selected: true },
        ]);
      });
      expect(result.current.nodes).toHaveLength(0);
    });
  });

  describe("onNodeDragStart and onNodeDrag", () => {
    it("calls highlight/expand without throwing", async () => {
      (getPossibleGraphIntersection as jest.Mock).mockReturnValue(undefined);
      const { result } = await withInitialNodes();

      act(() => {
        result.current.onNodeDragStart(
          {} as React.MouseEvent,
          draggedNode,
        );
      });
      act(() => {
        result.current.onNodeDrag({} as React.MouseEvent, draggedNode);
      });

      expect(getPossibleGraphIntersection).toHaveBeenCalled();
    });
  });

  describe("onDragOver", () => {
    it("prevents default and sets dropEffect", async () => {
      const { result } = await withInitialNodes();
      const preventDefault = jest.fn();
      const dataTransfer = { dropEffect: "none" as string };
      const event = {
        preventDefault,
        clientX: 10,
        clientY: 20,
        dataTransfer,
      } as unknown as React.DragEvent<HTMLDivElement>;

      act(() => {
        result.current.onDragOver(event);
      });

      expect(preventDefault).toHaveBeenCalled();
      expect(dataTransfer.dropEffect).toBe("move");
      expect(mockScreenToFlowPosition).toHaveBeenCalled();
    });
  });

  describe("updateNodeData", () => {
    it("leaves non-matching nodes untouched", async () => {
      const { result } = await withInitialNodes();

      const unrelatedNode = {
        id: "non-existent",
        type: "unit",
        position: { x: 0, y: 0 },
        data: {},
      } as unknown as ChainGraphNode;

      const element = {
        id: "non-existent",
        type: "script",
        name: "Won't match",
        description: "",
      } as unknown as Parameters<HookResult["updateNodeData"]>[0];

      const before = result.current.nodes;
      act(() => {
        result.current.updateNodeData(element, unrelatedNode);
      });
      const after = result.current.nodes;

      expect(after.map((n) => n.id)).toEqual(before.map((n) => n.id));
    });

    it("updates data of matching node only", async () => {
      const { result } = await withInitialNodes();

      const element = {
        id: "node-1",
        type: "script",
        name: "Updated",
        description: "",
        parentElementId: "container-1",
      } as unknown as Parameters<HookResult["updateNodeData"]>[0];

      act(() => {
        result.current.updateNodeData(element, draggedNode);
      });

      const updated = result.current.nodes.find((n) => n.id === "node-1");
      expect((updated?.data as { elementType?: string }).elementType).toBe(
        "script",
      );
      expect((updated?.data as { label?: string }).label).toBe("Updated");
      const untouched = result.current.nodes.find((n) => n.id === "container-1");
      expect(untouched).toBeDefined();
    });
  });

  describe("structureChanged", () => {
    it("is callable with explicit parent ids", async () => {
      const { result } = await withInitialNodes();
      expect(() => {
        act(() => {
          result.current.structureChanged(["container-1"]);
        });
      }).not.toThrow();
    });

    it("is callable without arguments", async () => {
      const { result } = await withInitialNodes();
      expect(() => {
        act(() => {
          result.current.structureChanged();
        });
      }).not.toThrow();
    });
  });

  describe("expand and collapse pass-through", () => {
    it("delegates to useExpandCollapse helpers", async () => {
      const { result } = await withInitialNodes();
      result.current.expandAllContainers();
      result.current.collapseAllContainers();

      expect(mockExpandAllContainers).toHaveBeenCalled();
      expect(mockCollapseAllContainers).toHaveBeenCalled();
    });
  });

  describe("onNodeDragStop", () => {
    it("calls onChainUpdate after successful transferElement", async () => {
      (getPossibleGraphIntersection as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      (getIntersectionParent as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      (findUpdatedElement as jest.Mock).mockReturnValue({
        id: "node-1",
        parentElementId: "container-2",
      });
      apiMock.transferElement.mockResolvedValue({
        updatedElements: [{ id: "node-1", parentElementId: "container-2" }],
      });

      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onNodeDragStop(
          {} as React.MouseEvent,
          draggedNode,
        );
      });

      expect(apiMock.transferElement).toHaveBeenCalledWith(
        expect.objectContaining({
          parentId: "container-2",
          swimlaneId: null,
          elements: ["node-1"],
        }),
        "chain-1",
      );
      expect(onChainUpdate).toHaveBeenCalled();
      expect(mockErrorWithDetails).not.toHaveBeenCalled();
    });

    it("sends swimlaneId when newParent is a swimlane", async () => {
      (getPossibleGraphIntersection as jest.Mock).mockReturnValue({
        id: "swimlane-1",
        type: "swimlane",
      });
      (getIntersectionParent as jest.Mock).mockReturnValue({
        id: "swimlane-1",
        type: "swimlane",
      });
      (findUpdatedElement as jest.Mock).mockReturnValue({
        id: "node-1",
        swimlaneId: "swimlane-1",
      });
      apiMock.transferElement.mockResolvedValue({
        updatedElements: [{ id: "node-1", swimlaneId: "swimlane-1" }],
      });

      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onNodeDragStop(
          {} as React.MouseEvent,
          draggedNode,
        );
      });

      expect(apiMock.transferElement).toHaveBeenCalledWith(
        expect.objectContaining({
          parentId: null,
          swimlaneId: "swimlane-1",
          elements: ["node-1"],
        }),
        "chain-1",
      );
      expect(onChainUpdate).toHaveBeenCalled();
    });

    it("does not call onChainUpdate when transferElement fails", async () => {
      (getPossibleGraphIntersection as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      (getIntersectionParent as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      apiMock.transferElement.mockRejectedValue(new Error("transfer failed"));

      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onNodeDragStop(
          {} as React.MouseEvent,
          draggedNode,
        );
      });

      expect(apiMock.transferElement).toHaveBeenCalled();
      expect(onChainUpdate).not.toHaveBeenCalled();
      expect(mockErrorWithDetails).toHaveBeenCalledWith(
        expect.stringContaining("Drag element failed"),
        expect.stringContaining("Failed to drag element"),
        expect.any(Error),
      );
    });

    it("does not call onChainUpdate when parent did not change", async () => {
      (getPossibleGraphIntersection as jest.Mock).mockReturnValue({
        id: "container-1",
        type: "container",
      });
      (getIntersectionParent as jest.Mock).mockReturnValue({
        id: "container-1",
        type: "container",
      });

      const onChainUpdate = jest.fn();
      const { result } = await withInitialNodes(onChainUpdate);

      await act(async () => {
        await result.current.onNodeDragStop(
          {} as React.MouseEvent,
          draggedNode,
        );
      });

      expect(apiMock.transferElement).not.toHaveBeenCalled();
      expect(onChainUpdate).not.toHaveBeenCalled();
    });

    it("does not call transferElement without chainId", async () => {
      const onChainUpdate = jest.fn();
      const rendered = await renderChainGraph(onChainUpdate, {
        chainId: undefined,
      });

      await act(async () => {
        await rendered.result.current.onNodeDragStop(
          {} as React.MouseEvent,
          draggedNode,
        );
      });

      expect(apiMock.transferElement).not.toHaveBeenCalled();
      expect(onChainUpdate).not.toHaveBeenCalled();
    });

    it("uses leastCommonParent when provided", async () => {
      (getPossibleGraphIntersection as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      (getIntersectionParent as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      (findUpdatedElement as jest.Mock).mockReturnValue({
        id: "node-1",
        parentElementId: "container-2",
      });
      (getLeastCommonParent as jest.Mock).mockReturnValue("root-container");
      apiMock.transferElement.mockResolvedValue({
        updatedElements: [{ id: "node-1", parentElementId: "container-2" }],
      });

      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onNodeDragStop(
          {} as React.MouseEvent,
          draggedNode,
        );
      });

      expect(getLeastCommonParent).toHaveBeenCalled();
    });

    it("falls back to dragged node id when nothing is selected", async () => {
      (getPossibleGraphIntersection as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      (getIntersectionParent as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      (findUpdatedElement as jest.Mock).mockReturnValue({
        id: "node-1",
        parentElementId: "container-2",
      });
      apiMock.transferElement.mockResolvedValue({
        updatedElements: [{ id: "node-1", parentElementId: "container-2" }],
      });

      const { result } = await renderChainGraph();

      const unselectedNodes = initialNodes.map((n) =>
        ({ ...n, selected: false }) as ChainGraphNode,
      );
      act(() => {
        result.current.setNodes(unselectedNodes);
      });
      await waitFor(() => {
        expect(result.current.nodes.length).toBe(unselectedNodes.length);
      });

      await act(async () => {
        await result.current.onNodeDragStop(
          {} as React.MouseEvent,
          { ...draggedNode, selected: false } as ChainGraphNode,
        );
      });

      expect(apiMock.transferElement).toHaveBeenCalledWith(
        expect.objectContaining({ elements: ["node-1"] }),
        "chain-1",
      );
    });

    it("returns early when dragged node is not in state", async () => {
      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onNodeDragStop(
          {} as React.MouseEvent,
          { ...draggedNode, id: "ghost" } as ChainGraphNode,
        );
      });

      expect(apiMock.transferElement).not.toHaveBeenCalled();
    });

    it("handles newParent with no specific type (falls back to null)", async () => {
      (getPossibleGraphIntersection as jest.Mock).mockReturnValue({
        id: "target",
        type: "other",
      });
      (getIntersectionParent as jest.Mock).mockReturnValue({
        id: "target",
        type: "other",
      });
      (findUpdatedElement as jest.Mock).mockReturnValue({ id: "node-1" });
      apiMock.transferElement.mockResolvedValue({ updatedElements: [] });

      const { result } = await withInitialNodes();

      await act(async () => {
        await result.current.onNodeDragStop(
          {} as React.MouseEvent,
          draggedNode,
        );
      });

      expect(apiMock.transferElement).toHaveBeenCalledWith(
        expect.objectContaining({ parentId: null, swimlaneId: null }),
        "chain-1",
      );
    });

    it("calls structureChanged() without args when no parents are affected", async () => {
      const rootLevelNode = {
        id: "root-node",
        type: "unit",
        position: { x: 0, y: 0 },
        data: {},
        selected: true,
      } as unknown as ChainGraphNode;
      const rootNodes = [
        rootLevelNode,
        {
          id: "container-2",
          type: "container",
          position: { x: 200, y: 0 },
          data: { elementType: "container" },
        },
      ] as unknown as ChainGraphNode[];

      (getPossibleGraphIntersection as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      (getIntersectionParent as jest.Mock).mockReturnValue({
        id: "container-2",
        type: "container",
      });
      (getLeastCommonParent as jest.Mock).mockReturnValue(undefined);
      apiMock.transferElement.mockRejectedValue(new Error("fail"));

      const { result } = await renderChainGraph();
      act(() => {
        result.current.setNodes(rootNodes);
      });
      await waitFor(() => {
        expect(result.current.nodes.length).toBe(rootNodes.length);
      });

      await act(async () => {
        await result.current.onNodeDragStop(
          {} as React.MouseEvent,
          rootLevelNode,
        );
      });

      expect(apiMock.transferElement).toHaveBeenCalled();
    });

    it("skips while library is loading", async () => {
      mockIsLibraryLoading = true;
      const onChainUpdate = jest.fn();
      const rendered = await renderChainGraph(onChainUpdate);

      await act(async () => {
        await rendered.result.current.onNodeDragStop(
          {} as React.MouseEvent,
          draggedNode,
        );
      });

      expect(apiMock.transferElement).not.toHaveBeenCalled();
    });
  });
});
