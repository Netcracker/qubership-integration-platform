/**
 * @jest-environment jsdom
 */

import { renderHook, act } from "@testing-library/react";
import { useAddChildContextMenuItem } from "../../../../src/hooks/graph/context_menu/useAddChildContextMenuItem.tsx";
import { useLibraryContext } from "../../../../src/components/LibraryContext.tsx";
import { useNotificationService } from "../../../../src/hooks/useNotificationService.tsx";
import { api } from "../../../../src/api/api.ts";
import { useAutoLayout } from "../../../../src/hooks/graph/useAutoLayout.tsx";
import { useExpandCollapse } from "../../../../src/hooks/graph/useExpandCollapse.tsx";
import {
  buildGraphNodes,
  findLibraryElement,
  getLibraryElement,
  getNodeFromElement,
  sortParentsBeforeChildren,
} from "../../../../src/misc/chain-graph-utils.ts";
import {
  ChainGraphNodeData,
  ChainGraphNode,
} from "../../../../src/components/graph/nodes/ChainGraphNodeTypes.ts";
import { Edge, Node } from "@xyflow/react";
import { ContextMenuItemsHookProps } from "../../../../src/hooks/graph/context_menu/useContextMenu.tsx";

// Mock all dependencies with explicit implementations
jest.mock("../../../../src/components/LibraryContext.tsx", () => ({
  useLibraryContext: jest.fn(),
}));

jest.mock("../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: jest.fn(),
}));

jest.mock("../../../../src/api/api.ts", () => ({
  api: {
    createElement: jest.fn(),
  },
}));

jest.mock("../../../../src/hooks/graph/useAutoLayout.tsx", () => ({
  useAutoLayout: jest.fn(),
}));

jest.mock("../../../../src/hooks/graph/useExpandCollapse.tsx", () => ({
  useExpandCollapse: jest.fn(),
}));

jest.mock("../../../../src/misc/chain-graph-utils.ts", () => ({
  buildGraphNodes: jest.fn(),
  findLibraryElement: jest.fn(),
  getLibraryElement: jest.fn(),
  getNodeFromElement: jest.fn(),
  sortParentsBeforeChildren: jest.fn(),
}));

jest.mock("uuid", () => ({
  v4: jest.fn(() => "mock-uuid"),
}));

const mockUseLibraryContext = useLibraryContext as jest.Mock;
const mockUseNotificationService = useNotificationService as jest.Mock;
const mockApi = api as unknown as { createElement: jest.Mock };
const mockUseAutoLayout = useAutoLayout as jest.Mock;
const mockUseExpandCollapse = useExpandCollapse as jest.Mock;
const mockBuildGraphNodes = buildGraphNodes as jest.Mock;
const mockFindLibraryElement = findLibraryElement as jest.Mock;
const mockGetLibraryElement = getLibraryElement as jest.Mock;
const mockGetNodeFromElement = getNodeFromElement as jest.Mock;
const mockSortParentsBeforeChildren = sortParentsBeforeChildren as jest.Mock;

describe("useAddChildContextMenuItem", () => {
  const mockLibraryElements = [
    {
      name: "parent",
      title: "Parent Element",
      container: true,
      allowedChildren: {
        "child-1": { name: "child-1", title: "Child 1" },
        "child-2": { name: "child-2", title: "Child 2" },
      },
    },
    {
      name: "child-1",
      title: "Child 1 Title",
      container: false,
      allowedChildren: {},
    },
    {
      name: "child-2",
      title: "Child 2 Title",
      container: false,
      allowedChildren: {},
    },
  ];

  const mockNodes: Node<ChainGraphNodeData>[] = [
    {
      id: "node-1",
      type: "chain",
      position: { x: 0, y: 0 },
      data: { elementType: "parent", label: "Parent" },
    } as unknown as Node<ChainGraphNodeData>,
  ];

  const mockEdges: Edge[] = [];
  const mockSetNodes = jest.fn();
  const mockSetEdges = jest.fn();
  const mockStructureChanged = jest.fn();
  const mockOnChainUpdate = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();

    mockUseLibraryContext.mockReturnValue({
      libraryElements: mockLibraryElements,
    });
    mockUseNotificationService.mockReturnValue({
      errorWithDetails: jest.fn(),
    });
    mockUseAutoLayout.mockReturnValue({
      arrangeNodes: jest
        .fn()
        .mockResolvedValue([
          { id: "new-node-1", position: { x: 100, y: 100 } } as ChainGraphNode,
        ]),
      direction: "TB",
    });
    mockUseExpandCollapse.mockReturnValue({
      attachToggle: jest.fn((nodes) => nodes),
      setNestedUnitCounts: jest.fn((nodes) => nodes),
    });
    mockBuildGraphNodes.mockReturnValue([]);
    mockFindLibraryElement.mockImplementation(
      (key: string) =>
        mockLibraryElements.find((el) => el.name === key) ||
        mockLibraryElements[0],
    );
    mockGetLibraryElement.mockReturnValue(mockLibraryElements[0]);
    mockGetNodeFromElement.mockReturnValue({
      id: "node-1",
      position: { x: 0, y: 0 },
    });
    mockSortParentsBeforeChildren.mockImplementation((nodes) => nodes);
  });

  describe("when building context menu items", () => {
    it("should return empty array when element is not a container", () => {
      mockFindLibraryElement.mockReturnValue({
        ...mockLibraryElements[1],
        container: false,
      });

      const { result } = renderHook(() =>
        useAddChildContextMenuItem({
          nodes: mockNodes,
          setNodes: mockSetNodes,
          edges: mockEdges,
          setEdges: mockSetEdges,
          structureChanged: mockStructureChanged,
          chainId: "chain-1",
          onChainUpdate: mockOnChainUpdate,
        } as unknown as ContextMenuItemsHookProps),
      );

      act(() => {
        result.current.buildItems(mockNodes);
      });

      expect(result.current.buildItems(mockNodes)).toEqual([]);
    });

    it("should return empty array when container has no allowed children", () => {
      mockFindLibraryElement.mockReturnValue({
        ...mockLibraryElements[0],
        container: true,
        allowedChildren: {},
      });

      const { result } = renderHook(() =>
        useAddChildContextMenuItem({
          nodes: mockNodes,
          setNodes: mockSetNodes,
          edges: mockEdges,
          setEdges: mockSetEdges,
          structureChanged: mockStructureChanged,
          chainId: "chain-1",
          onChainUpdate: mockOnChainUpdate,
        } as unknown as ContextMenuItemsHookProps),
      );

      act(() => {
        result.current.buildItems(mockNodes);
      });

      expect(result.current.buildItems(mockNodes)).toEqual([]);
    });

    it("should return add child menu item when container has allowed children", () => {
      mockFindLibraryElement.mockImplementation((key: string) =>
        key === "parent"
          ? mockLibraryElements[0]
          : mockLibraryElements.find((el) => el.name === key),
      );

      const { result } = renderHook(() =>
        useAddChildContextMenuItem({
          nodes: mockNodes,
          setNodes: mockSetNodes,
          edges: mockEdges,
          setEdges: mockSetEdges,
          structureChanged: mockStructureChanged,
          chainId: "chain-1",
          onChainUpdate: mockOnChainUpdate,
        } as unknown as ContextMenuItemsHookProps),
      );

      const items = result.current.buildItems(mockNodes);

      expect(items).toHaveLength(1);
      expect(items[0].text).toBe("Add child");
      expect(items[0].children).toHaveLength(2);
      expect(items[0].children?.[0].text).toBe("Child 1 Title");
      expect(items[0].children?.[1].text).toBe("Child 2 Title");
    });
  });

  describe("when creating child element", () => {
    it("should call api.createElement with correct parameters when createChildElement is invoked", async () => {
      mockApi.createElement.mockResolvedValue({
        createdElements: [{ id: "new-element", type: "child-1" }],
        updatedElements: [],
      });

      const { result } = renderHook(() =>
        useAddChildContextMenuItem({
          nodes: mockNodes,
          setNodes: mockSetNodes,
          edges: mockEdges,
          setEdges: mockSetEdges,
          structureChanged: mockStructureChanged,
          chainId: "chain-1",
          onChainUpdate: mockOnChainUpdate,
        } as unknown as ContextMenuItemsHookProps),
      );

      await act(async () => {
        await result.current.buildItems(mockNodes)[0].children![0].handler();
      });

      expect(mockApi.createElement).toHaveBeenCalledWith(
        { type: "child-1", parentElementId: "node-1" },
        "chain-1",
      );
    });

    it("should update nodes and call structureChanged when element is created successfully", async () => {
      mockApi.createElement.mockResolvedValue({
        createdElements: [{ id: "new-element", type: "child-1" }],
        updatedElements: [],
      });

      const { result } = renderHook(() =>
        useAddChildContextMenuItem({
          nodes: mockNodes,
          setNodes: mockSetNodes,
          edges: mockEdges,
          setEdges: mockSetEdges,
          structureChanged: mockStructureChanged,
          chainId: "chain-1",
          onChainUpdate: mockOnChainUpdate,
        } as unknown as ContextMenuItemsHookProps),
      );

      await act(async () => {
        await result.current.buildItems(mockNodes)[0].children![0].handler();
      });

      expect(mockSetNodes).toHaveBeenCalled();
      expect(mockStructureChanged).toHaveBeenCalled();
    });

    it("should not call onChainUpdate when it is not provided", async () => {
      mockApi.createElement.mockResolvedValue({
        createdElements: [{ id: "new-element", type: "child-1" }],
        updatedElements: [],
      });

      const { result } = renderHook(() =>
        useAddChildContextMenuItem({
          nodes: mockNodes,
          setNodes: mockSetNodes,
          edges: mockEdges,
          setEdges: mockSetEdges,
          structureChanged: mockStructureChanged,
          chainId: "chain-1",
          onChainUpdate: undefined,
        } as unknown as ContextMenuItemsHookProps),
      );

      await act(async () => {
        await result.current.buildItems(mockNodes)[0].children![0].handler();
      });

      expect(mockOnChainUpdate).not.toHaveBeenCalled();
    });

    it("should show error notification when api.createElement fails", async () => {
      const mockErrorNotification = jest.fn();
      mockUseNotificationService.mockReturnValue({
        errorWithDetails: mockErrorNotification,
      });
      mockApi.createElement.mockRejectedValue(new Error("API Error"));

      const { result } = renderHook(() =>
        useAddChildContextMenuItem({
          nodes: mockNodes,
          setNodes: mockSetNodes,
          edges: mockEdges,
          setEdges: mockSetEdges,
          structureChanged: mockStructureChanged,
          chainId: "chain-1",
          onChainUpdate: mockOnChainUpdate,
        } as unknown as ContextMenuItemsHookProps),
      );

      await act(async () => {
        await result.current.buildItems(mockNodes)[0].children![0].handler();
      });

      expect(mockErrorNotification).toHaveBeenCalledWith(
        "Failed to create element",
        expect.any(String),
        expect.any(Error),
      );
    });
  });
});
